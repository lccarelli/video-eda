package spike

import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

@Provider("videos")
@PactFolder("build/pacts")
class VideosProviderVerificationTest : VideosProviderContract() {
    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun `CTR-01 videos cumple los pacts de sus consumers`(context: PactVerificationContext) {
        context.verifyInteraction()
    }
}
