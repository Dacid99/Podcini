package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.storage.database.RealmDB.upsertBlk
import ac.mdiq.podcini.storage.utils.MediaMetadataRetrieverCompat
import ac.mdiq.podcini.util.Logd
import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import io.realm.kotlin.types.EmbeddedRealmObject
import io.realm.kotlin.types.annotations.Ignore
import io.realm.kotlin.types.annotations.Index
import java.io.File
import java.util.*
import kotlin.math.max

class EpisodeMedia: EmbeddedRealmObject, Playable {

    @Index
    var id: Long = 0L   // same as the episode id

    var fileUrl: String? = null
//        set(value) {
//            field = value
//            if (value == null) downloaded = false
//        }

    var downloadUrl: String? = null

    var downloaded: Boolean = false
//        set(value) {
//            Logd(TAG, "setting downloaded: $value ${episode?.isNew}")
//            field = value
//            if (value && episode?.isNew == true) episode!!.setPlayed(false)
//        }

    var downloadTime: Long = 0

    @get:JvmName("getDurationProperty")
    @set:JvmName("setDurationProperty")
    var duration = 0

    @get:JvmName("getPositionProperty")
    @set:JvmName("setPositionProperty")
    var position = 0 // Current position in file

    @get:JvmName("getLastPlayedTimeProperty")
    @set:JvmName("setLastPlayedTimeProperty")
    var lastPlayedTime: Long = 0 // Last time this media was played (in ms)

    var playedDuration: Int = 0 // How many ms of this file have been played

    var size: Long // File size in Byte

    var mimeType: String?
        private set

    var episode: Episode?

    var playbackCompletionTime: Long = 0
    @Ignore
    var playbackCompletionDate: Date? = null
        get() = field?.clone() as? Date
        set(value) {
            field = value?.clone() as? Date
            this.playbackCompletionTime = value?.time ?: 0
        }

    var startPosition: Int = -1

    var playedDurationWhenStarted: Int = 0
        private set

    // if null: unknown, will be checked
    var hasEmbeddedPicture: Boolean? = null

    /* Used for loading item when restoring from parcel. */
    var episodeId: Long = 0
        private set

    @Ignore
    val isInProgress: Boolean
        get() = (this.position > 0)

    constructor() {
        this.size = 0
        this.mimeType = ""
        this.episode = null
    }

    constructor(i: Episode?, download_url: String?, size: Long, mime_type: String?) {
        this.episode = i
        this.size = size
        this.mimeType = mime_type
        setfileUrlOrNull(null)
        this.downloadUrl = download_url
    }

    // mostly used in tests
    constructor(id: Long, item: Episode?, duration: Int, position: Int,
                size: Long, mime_type: String?, file_url: String?, download_url: String?,
                downloaded: Boolean, playbackCompletionDate: Date?, played_duration: Int,
                lastPlayedTime: Long) {
        this.id = id
        this.episode = item
        this.duration = duration
        this.position = position
        this.playedDuration = played_duration
        this.playedDurationWhenStarted = played_duration
        this.size = size
        this.mimeType = mime_type
        this.playbackCompletionDate =  playbackCompletionDate?.clone() as? Date
        this.playbackCompletionTime =  playbackCompletionDate?.time ?: 0
        this.lastPlayedTime = lastPlayedTime
        setfileUrlOrNull(file_url)
        this.downloadUrl = download_url
        if (downloaded) setIsDownloaded()
        else this.downloaded = downloaded
    }

    fun getHumanReadableIdentifier(): String? {
        return if (episode?.title != null) episode!!.title else downloadUrl
    }

    /**
     * Uses mimetype to determine the type of media.
     */
    override fun getMediaType(): MediaType {
        return MediaType.fromMimeType(mimeType)
    }

    fun updateFromOther(other: EpisodeMedia) {
        this.downloadUrl = other.downloadUrl

        if (other.size > 0) size = other.size
        // Do not overwrite duration that we measured after downloading
        if (other.duration > 0 && duration <= 0) duration = other.duration
        if (other.mimeType != null) mimeType = other.mimeType
    }

    fun compareWithOther(other: EpisodeMedia): Boolean {
        if (downloadUrl != other.downloadUrl) return true

        if (other.mimeType != null) {
            if (mimeType == null || mimeType != other.mimeType) return true
        }
        if (other.size > 0 && other.size != size) return true
        if (other.duration > 0 && duration <= 0) return true

        return false
    }

    fun getTypeAsInt(): Int {
        return FEEDFILETYPE_FEEDMEDIA
    }

    fun setIsDownloaded() {
        downloaded = true
        downloadTime = Date().time
        if (episode?.isNew == true) episode!!.setPlayed(false)
    }

    fun setfileUrlOrNull(url: String?) {
        fileUrl = url
        if (url == null) downloaded = false
    }

    override fun getDuration(): Int {
        return duration
    }

    override fun setDuration(newDuration: Int) {
        this.duration = newDuration
    }
    override fun getPosition(): Int {
        return position
    }

    override fun setPosition(newPosition: Int) {
        this.position = newPosition
        if (newPosition > 0 && episode != null && episode!!.isNew) episode!!.setPlayed(false)
    }

    override fun getLastPlayedTime(): Long {
        return lastPlayedTime
    }

    override fun setLastPlayedTime(lastPlayedTime: Long) {
        this.lastPlayedTime = lastPlayedTime
    }

    override fun getDescription(): String? {
        return episode?.description
    }

    fun fileExists(): Boolean {
        if (fileUrl == null) return false
        else {
            val f = File(fileUrl!!)
            return f.exists()
        }
    }

    /**
     * Indicates we asked the service what the size was, but didn't
     * get a valid answer and we shoudln't check using the network again.
     */
    fun setCheckedOnSizeButUnknown() {
        this.size = CHECKED_ON_SIZE_BUT_UNKNOWN.toLong()
    }

    fun checkedOnSizeButUnknown(): Boolean {
        return (CHECKED_ON_SIZE_BUT_UNKNOWN.toLong() == this.size)
    }

    override fun describeContents(): Int {
        return 0
    }

    fun hasEmbeddedPicture(): Boolean {
//        TODO: checkEmbeddedPicture needs to update current copy
        if (hasEmbeddedPicture == null) checkEmbeddedPicture()
        return hasEmbeddedPicture ?: false
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id.toString())
        dest.writeString(if (episode != null) episode!!.id.toString() else "")

        dest.writeInt(duration)
        dest.writeInt(position)
        dest.writeLong(size)
        dest.writeString(mimeType)
        dest.writeString(fileUrl)
        dest.writeString(downloadUrl)
        dest.writeByte((if (downloaded) 1 else 0).toByte())
        dest.writeLong(playbackCompletionDate?.time ?: 0)
        dest.writeInt(playedDuration)
        dest.writeLong(lastPlayedTime)
    }

//    no longer needed
//    override fun writeToPreferences(prefEditor: SharedPreferences.Editor) {
//        if (episode == null) prefEditor.putLong(PREF_FEED_ID, 0L)
//        else {
//            val f = episode!!.feed
//            if (f != null) prefEditor.putLong(PREF_FEED_ID, f.id)
//            else prefEditor.putLong(PREF_FEED_ID, 0L)
//        }
//
//        prefEditor.putLong(PREF_MEDIA_ID, id)
//    }

    override fun getEpisodeTitle(): String {
        return episode?.title ?: episode?.identifyingValue ?: "No title"
    }

    override fun getChapters(): List<Chapter> {
        return episode?.chapters?:listOf()
    }

    override fun chaptersLoaded(): Boolean {
        return episode?.chapters != null
    }

    override fun getWebsiteLink(): String? {
        return episode?.link
    }

    override fun getFeedTitle(): String {
        return episode?.feed?.title?:""
    }

    override fun getIdentifier(): Any {
        return id
    }

    override fun getLocalMediaUrl(): String? {
        return fileUrl
    }

    override fun getStreamUrl(): String? {
        return downloadUrl
    }

    override fun getPubDate(): Date? {
        return episode?.getPubDate()
    }

    override fun localFileAvailable(): Boolean {
        return downloaded && fileUrl != null
    }

    override fun onPlaybackStart() {
        startPosition = max(position.toDouble(), 0.0).toInt()
        playedDurationWhenStarted = playedDuration
    }

    override fun onPlaybackPause(context: Context) {
        Logd("EpisodeMedia", "onPlaybackPause $position $duration")
        if (position > startPosition) {
            playedDuration = playedDurationWhenStarted + position - startPosition
            playedDurationWhenStarted = playedDuration
        }
        startPosition = position
    }

    override fun onPlaybackCompleted(context: Context) {
        startPosition = -1
    }

    override fun getPlayableType(): Int {
        return PLAYABLE_TYPE_FEEDMEDIA
    }

    override fun setChapters(chapters: List<Chapter>) {
        if (episode != null) {
            episode!!.chapters.clear()
            for (c in chapters) {
                c.episode = episode
            }
            episode!!.chapters.addAll(chapters)
        }
    }

    override fun getImageLocation(): String? {
        return when {
            episode != null -> episode!!.imageLocation
            hasEmbeddedPicture() -> FILENAME_PREFIX_EMBEDDED_COVER + getLocalMediaUrl()
            else -> null
        }
    }

    fun checkEmbeddedPicture() {
        if (!localFileAvailable()) hasEmbeddedPicture = false
        else {
            try {
                MediaMetadataRetrieverCompat().use { mmr ->
                    mmr.setDataSource(getLocalMediaUrl())
                    val image = mmr.embeddedPicture
                    hasEmbeddedPicture = image != null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                hasEmbeddedPicture = false
            }
        }
        upsertBlk(episode!!) {}
    }

    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (other is RemoteMedia) return other == this
        return super.equals(other)
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + duration
        result = 31 * result + position
        result = 31 * result + lastPlayedTime.hashCode()
        result = 31 * result + playedDuration
        result = 31 * result + size.hashCode()
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + (episode?.hashCode() ?: 0)
        result = 31 * result + (playbackCompletionDate?.hashCode() ?: 0)
        result = 31 * result + startPosition
        result = 31 * result + playedDurationWhenStarted
        result = 31 * result + (hasEmbeddedPicture?.hashCode() ?: 0)
        result = 31 * result + episodeId.hashCode()
        return result
    }

    companion object {
        private val TAG: String = EpisodeMedia::class.simpleName ?: "Anonymous"

        const val FEEDFILETYPE_FEEDMEDIA: Int = 2
        const val PLAYABLE_TYPE_FEEDMEDIA: Int = 1
        const val FILENAME_PREFIX_EMBEDDED_COVER: String = "metadata-retriever:"

//        const val PREF_MEDIA_ID: String = "EpisodeMedia.PrefMediaId"
//        private const val PREF_FEED_ID = "EpisodeMedia.PrefFeedId"

        /**
         * Indicates we've checked on the size of the item via the network
         * and got an invalid response. Using Integer.MIN_VALUE because
         * 1) we'll still check on it in case it gets downloaded (it's <= 0)
         * 2) By default all EpisodeMedia have a size of 0 if we don't know it,
         * so this won't conflict with existing practice.
         */
        private const val CHECKED_ON_SIZE_BUT_UNKNOWN = Int.MIN_VALUE

        @JvmField
        val CREATOR: Parcelable.Creator<EpisodeMedia> = object : Parcelable.Creator<EpisodeMedia> {
            override fun createFromParcel(inVal: Parcel): EpisodeMedia {
                val id = inVal.readLong()
                val itemID = inVal.readLong()
                val result = EpisodeMedia(id,
                    null,
                    inVal.readInt(),
                    inVal.readInt(),
                    inVal.readLong(),
                    inVal.readString(),
                    inVal.readString(),
                    inVal.readString(),
                    inVal.readByte().toInt() != 0,
                    Date(inVal.readLong()),
                    inVal.readInt(),
                    inVal.readLong())
                result.episodeId = itemID
                return result
            }

            override fun newArray(size: Int): Array<EpisodeMedia?> {
                return arrayOfNulls(size)
            }
        }
    }
}