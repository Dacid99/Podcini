package ac.mdiq.podcini.ui.fragment

import ac.mdiq.podcini.R
import ac.mdiq.podcini.databinding.BaseEpisodesListFragmentBinding
import ac.mdiq.podcini.databinding.MultiSelectSpeedDialBinding
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.EpisodeFilter
import ac.mdiq.podcini.storage.utils.EpisodeUtil
import ac.mdiq.podcini.ui.actions.handler.EpisodeMultiSelectHandler
import ac.mdiq.podcini.ui.actions.swipeactions.SwipeActions
import ac.mdiq.podcini.ui.activity.MainActivity
import ac.mdiq.podcini.ui.adapter.EpisodesAdapter
import ac.mdiq.podcini.ui.adapter.SelectableAdapter
import ac.mdiq.podcini.ui.dialog.ConfirmationDialog
import ac.mdiq.podcini.ui.utils.EmptyViewHandler
import ac.mdiq.podcini.ui.utils.LiftOnScrollListener
import ac.mdiq.podcini.ui.view.EpisodeViewHolder
import ac.mdiq.podcini.ui.view.EpisodesRecyclerView
import ac.mdiq.podcini.util.Logd
import ac.mdiq.podcini.util.EventFlow
import ac.mdiq.podcini.util.FlowEvent
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.util.Pair
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.leinardi.android.speeddial.SpeedDialActionItem
import com.leinardi.android.speeddial.SpeedDialView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@UnstableApi abstract class BaseEpisodesFragment : Fragment(), SelectableAdapter.OnSelectModeListener, Toolbar.OnMenuItemClickListener {
    val TAG = this::class.simpleName ?: "Anonymous"

    @JvmField
    protected var page: Int = 1
    protected var isLoadingMore: Boolean = false
    protected var hasMoreItems: Boolean = false
    private var displayUpArrow = false

    var _binding: BaseEpisodesListFragmentBinding? = null
    protected val binding get() = _binding!!

    lateinit var recyclerView: EpisodesRecyclerView
    lateinit var emptyView: EmptyViewHandler
    lateinit var speedDialView: SpeedDialView
    lateinit var toolbar: MaterialToolbar
    lateinit var swipeActions: SwipeActions
    private lateinit var progressBar: ProgressBar
    lateinit var adapter: EpisodesAdapter
    protected lateinit var txtvInformation: TextView

    @JvmField
    var episodes: MutableList<Episode> = ArrayList()

    @UnstableApi override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        super.onCreateView(inflater, container, savedInstanceState)

        _binding = BaseEpisodesListFragmentBinding.inflate(inflater)
        Logd(TAG, "fragment onCreateView")

        txtvInformation = binding.txtvInformation
        toolbar = binding.toolbar
        toolbar.setOnMenuItemClickListener(this)
        toolbar.setOnLongClickListener {
            recyclerView.scrollToPosition(5)
            recyclerView.post { recyclerView.smoothScrollToPosition(0) }
            false
        }
        displayUpArrow = parentFragmentManager.backStackEntryCount != 0
        if (savedInstanceState != null) displayUpArrow = savedInstanceState.getBoolean(KEY_UP_ARROW)

        (activity as MainActivity).setupToolbarToggle(toolbar, displayUpArrow)

        recyclerView = binding.recyclerView
        recyclerView.setRecycledViewPool((activity as MainActivity).recycledViewPool)
        setupLoadMoreScrollListener()
        recyclerView.addOnScrollListener(LiftOnScrollListener(binding.appbar))

        swipeActions = SwipeActions(this, TAG).attachTo(recyclerView)
        lifecycle.addObserver(swipeActions)
        swipeActions.setFilter(getFilter())
        refreshSwipeTelltale()
        binding.leftActionIcon.setOnClickListener { swipeActions.showDialog() }
        binding.rightActionIcon.setOnClickListener { swipeActions.showDialog() }

        val animator: RecyclerView.ItemAnimator? = recyclerView.itemAnimator
        if (animator is SimpleItemAnimator) animator.supportsChangeAnimations = false

        createListAdaptor()

        progressBar = binding.progressBar
        progressBar.visibility = View.VISIBLE

        emptyView = EmptyViewHandler(requireContext())
        emptyView.attachToRecyclerView(recyclerView)
        emptyView.setIcon(R.drawable.ic_feed)
        emptyView.setTitle(R.string.no_all_episodes_head_label)
        emptyView.setMessage(R.string.no_all_episodes_label)
        emptyView.updateAdapter(adapter)
        emptyView.hide()

        val multiSelectDial = MultiSelectSpeedDialBinding.bind(binding.root)
        speedDialView = multiSelectDial.fabSD
        speedDialView.overlayLayout = multiSelectDial.fabSDOverlay
        speedDialView.inflate(R.menu.episodes_apply_action_speeddial)
        speedDialView.setOnChangeListener(object : SpeedDialView.OnChangeListener {
            override fun onMainActionSelected(): Boolean {
                return false
            }
            override fun onToggleChanged(open: Boolean) {
                if (open && adapter.selectedCount == 0) {
                    (activity as MainActivity).showSnackbarAbovePlayer(R.string.no_items_selected, Snackbar.LENGTH_SHORT)
                    speedDialView.close()
                }
            }
        })
        speedDialView.setOnActionSelectedListener { actionItem: SpeedDialActionItem ->
//            if (actionItem.id == R.id.put_in_queue_batch) {
//                EpisodeMultiSelectHandler((activity as MainActivity), actionItem.id).handleAction(adapter.selectedItems.filterIsInstance<Episode>())
//                true
//            } else {
                var confirmationString = 0
            Logd(TAG, "adapter.selectedItems: ${adapter.selectedItems.size}")
                if (adapter.selectedItems.size >= 25 || adapter.shouldSelectLazyLoadedItems()) {
                    when (actionItem.id) {
                        R.id.toggle_played_batch -> confirmationString = R.string.multi_select_toggle_played_confirmation
                        else -> confirmationString = R.string.multi_select_operation_confirmation
                    }
                }
                if (confirmationString == 0) performMultiSelectAction(actionItem.id)
                else {
                    object : ConfirmationDialog(activity as MainActivity, R.string.multi_select, confirmationString) {
                        override fun onConfirmButtonPressed(dialog: DialogInterface) {
                            performMultiSelectAction(actionItem.id)
                        }
                    }.createNewDialog().show()
                }
                true
//            }
        }
        return binding.root
    }

    open fun createListAdaptor() {
        adapter = object : EpisodesAdapter(activity as MainActivity) {}
        adapter.setOnSelectModeListener(this)
        recyclerView.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        procFlowEvents()
        loadItems()
    }

    override fun onStop() {
        super.onStop()
        cancelFlowEvents()
        val recyclerView =  binding.recyclerView
        val childCount = recyclerView.childCount
        for (i in 0 until childCount) {
            val child = recyclerView.getChildAt(i)
            val viewHolder = recyclerView.getChildViewHolder(child) as? EpisodeViewHolder
            viewHolder?.stopDBMonitor()
        }
    }

    override fun onResume() {
        super.onResume()
        registerForContextMenu(recyclerView)
    }

    override fun onPause() {
        super.onPause()
        recyclerView.saveScrollPosition(getPrefName())
        unregisterForContextMenu(recyclerView)
    }

    @Deprecated("Deprecated in Java")
    @UnstableApi override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (super.onOptionsItemSelected(item)) return true
        val itemId = item.itemId
        when (itemId) {
            R.id.action_search -> {
                (activity as MainActivity).loadChildFragment(SearchFragment.newInstance())
                return true
            }
            else -> return false
        }
    }

    @UnstableApi private fun performMultiSelectAction(actionItemId: Int) {
        val handler = EpisodeMultiSelectHandler((activity as MainActivity), actionItemId)
        handler.handleAction(adapter.selectedItems)
//        TODO: this appears incorrect, and lazy loading is likely not needed
//        lifecycleScope.launch {
//            try {
//                withContext(Dispatchers.IO) {
//                    handler.handleAction(adapter.selectedItems.filterIsInstance<Episode>())
//                    if (adapter.shouldSelectLazyLoadedItems()) {
//                        var applyPage = page + 1
//                        var nextPage: List<Episode>
//                        do {
//                            nextPage = loadMoreData(applyPage)
//                            handler.handleAction(nextPage)
//                            applyPage++
//                        } while (nextPage.size == EPISODES_PER_PAGE)
//                    }
//                    withContext(Dispatchers.Main) {
//                        adapter.endSelectMode()
//                    }
//                }
//            } catch (e: Throwable) {
//                Log.e(TAG, Log.getStackTraceString(e))
//            }
//        }
    }

    private fun setupLoadMoreScrollListener() {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, deltaX: Int, deltaY: Int) {
                super.onScrolled(view, deltaX, deltaY)
//                Logd(TAG, "addOnScrollListener called isLoadingMore:$isLoadingMore hasMoreItems:$hasMoreItems ${recyclerView.isScrolledToBottom}")
                if (!isLoadingMore && hasMoreItems && recyclerView.isScrolledToBottom) {
                    /* The end of the list has been reached. Load more data. */
                    page++
                    loadMoreItems()
//                    isLoadingMore = true
                }
            }
        })
    }

    private fun loadMoreItems() {
        Logd(TAG, "loadMoreItems() called $page")

        isLoadingMore = true
//        listAdapter.setDummyViews(1)
//        listAdapter.notifyItemInserted(listAdapter.itemCount - 1)
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val data_ = loadMoreData(page)
                    if (data_.size < EPISODES_PER_PAGE) hasMoreItems = false
                    Logd(TAG, "loadMoreItems $page ${data_.size}")
                    episodes.addAll(data_)
                    data_
                }
                withContext(Dispatchers.Main) {
//                    listAdapter.setDummyViews(0)
                    adapter.updateItems(episodes)
                    if (adapter.shouldSelectLazyLoadedItems()) adapter.setSelected(episodes.size - data.size, episodes.size, true)
                }
            } catch (e: Throwable) {
//                listAdapter.setDummyViews(0)
                adapter.updateItems(mutableListOf())
                Log.e(TAG, Log.getStackTraceString(e))
            } finally {
                withContext(Dispatchers.Main) { recyclerView.post { isLoadingMore = false } }
            }
        }
    }

    override fun onDestroyView() {
        Logd(TAG, "onDestroyView")
        _binding = null
        adapter.clearData()
        adapter.endSelectMode()
        episodes.clear()
        super.onDestroyView()
    }

    override fun onStartSelectMode() {
        speedDialView.visibility = View.VISIBLE
    }

    override fun onEndSelectMode() {
        speedDialView.close()
        speedDialView.visibility = View.GONE
    }

    private fun onKeyUp(event: KeyEvent) {
        if (!isAdded || !isVisible || !isMenuVisible) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_T -> recyclerView.smoothScrollToPosition(0)
            KeyEvent.KEYCODE_B -> recyclerView.smoothScrollToPosition(adapter.itemCount)
            else -> {}
        }
    }

    private fun onEpisodeDownloadEvent(event: FlowEvent.EpisodeDownloadEvent) {
        if (loadItemsRunning) return
        for (downloadUrl in event.urls) {
            val pos: Int = EpisodeUtil.indexOfItemWithDownloadUrl(episodes, downloadUrl)
            if (pos >= 0) adapter.notifyItemChangedCompat(pos)
        }
    }

    private var eventSink: Job? = null
    private var eventStickySink: Job? = null
    private var eventKeySink: Job?     = null
    private fun cancelFlowEvents() {
        eventSink?.cancel()
        eventSink = null
        eventStickySink?.cancel()
        eventStickySink = null
        eventKeySink?.cancel()
        eventKeySink = null
    }
    private fun procFlowEvents() {
        if (eventSink == null) eventSink = lifecycleScope.launch {
            EventFlow.events.collectLatest { event ->
                Logd(TAG, "Received event: ${event.TAG}")
                when (event) {
                    is FlowEvent.SwipeActionsChangedEvent -> refreshSwipeTelltale()
                    is FlowEvent.FeedListEvent, is FlowEvent.EpisodePlayedEvent, is FlowEvent.PlayerSettingsEvent, is FlowEvent.FavoritesEvent -> loadItems()
                    else -> {}
                }
            }
        }
        if (eventStickySink == null) eventStickySink = lifecycleScope.launch {
            EventFlow.stickyEvents.collectLatest { event ->
                Logd(TAG, "Received sticky event: ${event.TAG}")
                when (event) {
                    is FlowEvent.EpisodeDownloadEvent -> onEpisodeDownloadEvent(event)
//                    is FlowEvent.FeedUpdatingEvent -> onFeedUpdateRunningEvent(event)
                    else -> {}
                }
            }
        }
        if (eventKeySink == null) eventKeySink = lifecycleScope.launch {
            EventFlow.keyEvents.collectLatest { event ->
                Logd(TAG, "Received key event: $event")
                onKeyUp(event)
            }
        }
    }

    private fun refreshSwipeTelltale() {
        if (swipeActions.actions?.left != null) binding.leftActionIcon.setImageResource(swipeActions.actions!!.left!!.getActionIcon())
        if (swipeActions.actions?.right != null) binding.rightActionIcon.setImageResource(swipeActions.actions!!.right!!.getActionIcon())
    }

    private var loadItemsRunning = false
    fun loadItems() {
        if (!loadItemsRunning) {
            loadItemsRunning = true
            Logd(TAG, "loadItems() called")
            adapter.updateItems(mutableListOf())
            lifecycleScope.launch {
                try {
                    val data = withContext(Dispatchers.IO) {
                        Pair(loadData().toMutableList(), loadTotalItemCount())
                    }
                    withContext(Dispatchers.Main) {
                        val restoreScrollPosition = episodes.isEmpty()
                        episodes = data.first
                        hasMoreItems = !(page == 1 && episodes.size < EPISODES_PER_PAGE)
                        progressBar.visibility = View.GONE
//                    listAdapter.setDummyViews(0)
                        adapter.updateItems(episodes)
                        adapter.setTotalNumberOfItems(data.second)
                        if (restoreScrollPosition) recyclerView.restoreScrollPosition(getPrefName())
                        updateToolbar()
                    }
                } catch (e: Throwable) {
//                listAdapter.setDummyViews(0)
                    adapter.updateItems(mutableListOf())
                    Log.e(TAG, Log.getStackTraceString(e))
                } finally {
                    loadItemsRunning = false
                }
            }
        }
    }

    protected abstract fun loadData(): List<Episode>

    protected abstract fun loadMoreData(page: Int): List<Episode>

    protected abstract fun loadTotalItemCount(): Int

    open fun getFilter(): EpisodeFilter {
        return EpisodeFilter.unfiltered()
    }

    protected abstract fun getPrefName(): String

    protected open fun updateToolbar() {}

//    private fun onFeedUpdateRunningEvent(event: FlowEvent.FeedUpdatingEvent) {
//        swipeRefreshLayout.isRefreshing = event.isRunning
//    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_UP_ARROW, displayUpArrow)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_UP_ARROW = "up_arrow"
        const val EPISODES_PER_PAGE: Int = 50
    }
}
