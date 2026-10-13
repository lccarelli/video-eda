package videoeda.videos

import com.fasterxml.jackson.databind.ObjectMapper

data class ProducedRecord(val key: String, val value: String)

class VideoPublishedProducer(private val breakMode: String? = System.getProperty("videoeda.break")) {
    private val jsonMapper = ObjectMapper()

    fun videoPublished(): ProducedRecord {
        val videoId = "vid_01a13161-1277-7e7b-9435-b109a7e95989"
        val creatorId = "usr_01a13161-11be-7119-8e3e-bf07f13eb833"
        val data = linkedMapOf<String, Any?>(
            "videoId" to videoId,
            "creatorId" to creatorId,
            "title" to "Mi primer video",
            "durationSeconds" to 184,
            "publishedAt" to "2026-10-12T19:29:58Z",
        )
        when (breakMode) {
            "creatorId-missing" -> data.remove("creatorId")
            "creatorId-type" -> data["creatorId"] = 7
            "extra-optional-field" -> data["thumbnailUrl"] = "https://cdn.example/vid.jpg"
        }
        val event = linkedMapOf<String, Any?>(
            "specversion" to "1.0",
            "id" to "evt_01a13161-12e6-7e09-b67f-10ca57c43328",
            "source" to "/videos",
            "type" to "dev.videoeda.videos.video-published",
            "subject" to videoId,
            "time" to "2026-10-12T19:30:00Z",
            "datacontenttype" to "application/json",
            "correlationid" to "cor_01a13161-13c4-7752-b78c-4c2b8c8039fb",
            "sequence" to 1,
            "data" to data,
        )
        val key = if (breakMode == "key-wrong") creatorId else videoId
        return ProducedRecord(key, jsonMapper.writeValueAsString(event))
    }
}
