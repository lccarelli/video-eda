package spike

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import videoeda.videos.VideoPublishedProducer

class KeyAndSubjectContractTest {
    private val mapper = ObjectMapper()

    @Test
    fun `CTR-05 la key de videos video es el videoId y coincide con subject`() {
        val record = VideoPublishedProducer().videoPublished()
        val event = mapper.readTree(record.value)
        assertEquals(event.path("data").path("videoId").asText(), record.key, "key debe ser data.videoId")
        assertEquals(event.path("subject").asText(), record.key, "key debe ser subject")
    }

    @Test
    fun `CTR-05 detecta una key equivocada (creatorId en vez de videoId)`() {
        val record = VideoPublishedProducer(breakMode = "key-wrong").videoPublished()
        val event = mapper.readTree(record.value)
        val keyMatchesAggregate = event.path("subject").asText() == record.key
        assertEquals(false, keyMatchesAggregate, "con key-wrong el chequeo de key tiene que dar falso")
    }
}
