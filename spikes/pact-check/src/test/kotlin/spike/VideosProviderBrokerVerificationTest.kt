package spike

import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

@Provider("videos")
@PactBroker(url = "\${pactbroker.url}")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class VideosProviderBrokerVerificationTest : VideosProviderContract() {
    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun `CTR-01 videos cumple los pacts publicados en el broker`(context: PactVerificationContext) {
        context.verifyInteraction()
    }
}
