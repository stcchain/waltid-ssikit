package id.walt.signatory

import com.beust.klaxon.Json
import id.walt.credentials.w3c.VerifiableCredential
import id.walt.credentials.w3c.W3CIssuer
import id.walt.credentials.w3c.builder.AbstractW3CCredentialBuilder
import id.walt.credentials.w3c.builder.W3CCredentialBuilder
import id.walt.credentials.w3c.templates.VcTemplateManager
import id.walt.credentials.w3c.toVerifiableCredential
import id.walt.crypto.LdSignatureType
import id.walt.model.DidMethod
import id.walt.model.DidUrl
import id.walt.servicematrix.ServiceConfiguration
import id.walt.servicematrix.ServiceProvider
import id.walt.services.WaltIdService
import id.walt.services.context.ContextManager
import id.walt.services.did.DidService
import id.walt.services.vc.JsonLdCredentialService
import id.walt.services.vc.JwtCredentialService
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*

private val log = KotlinLogging.logger {}

enum class ProofType {
    JWT, LD_PROOF
}

enum class Ecosystem {
    DEFAULT,
    ESSIF,
    GAIAX,
    IOTA
}

data class ProofConfig(
    val issuerDid: String,
    @Json(serializeNull = false) val subjectDid: String? = null,
    @Json(serializeNull = false) val verifierDid: String? = null,
    @Json(serializeNull = false) val issuerVerificationMethod: String? = null, // DID URL that defines key ID; if null the issuers' default key is used
    val proofType: ProofType = ProofType.LD_PROOF,
    @Json(serializeNull = false) val domain: String? = null,
    @Json(serializeNull = false) val nonce: String? = null,
    @Json(serializeNull = false) val proofPurpose: String? = null,
    @Json(serializeNull = false) val credentialId: String? = null,
    @Json(serializeNull = false) val issueDate: Instant? = null, // issue date from json-input or current system time if null
    @Json(serializeNull = false) val validDate: Instant? = null, // valid date from json-input or current system time if null
    @Json(serializeNull = false) val expirationDate: Instant? = null,
    @Json(serializeNull = false) val dataProviderIdentifier: String? = null, // may be used for mapping data-sets from a custom data-provider
    @Json(serializeNull = false) val ldSignatureType: LdSignatureType? = null,
    @Json(serializeNull = false) val creator: String? = issuerDid,
    @Json(serializeNull = false) val ecosystem: Ecosystem = Ecosystem.DEFAULT
)

data class SignatoryConfig(
    val proofConfig: ProofConfig
) : ServiceConfiguration

abstract class Signatory : WaltIdService() {
    override val implementation: Signatory get() = serviceImplementation()

    companion object : ServiceProvider {
        override fun getService() = object : Signatory() {}
    }

    open fun issue(templateIdOrFilename: String, config: ProofConfig, dataProvider: SignatoryDataProvider? = null, issuer: W3CIssuer? = null): String =
        implementation.issue(templateIdOrFilename, config, dataProvider, issuer)

    open fun issue(credentialBuilder: AbstractW3CCredentialBuilder<*, *>, config: ProofConfig, issuer: W3CIssuer? = null): String = implementation.issue(credentialBuilder, config, issuer)

    open fun listTemplates(): List<String> = implementation.listTemplates()
    open fun loadTemplate(templateId: String): VerifiableCredential = implementation.loadTemplate(templateId)
}

class WaltIdSignatory(configurationPath: String) : Signatory() {

    private val VC_GROUP = "signatory"
    override val configuration: SignatoryConfig = fromConfiguration(configurationPath)

    private fun defaultLdSignatureByDidMethod(did: String): LdSignatureType? {
        val didUrl = DidUrl.from(did)
        return when (didUrl.method) {
            DidMethod.iota.name -> LdSignatureType.JcsEd25519Signature2020
            else -> null
        }
    }

    private fun issuerVerificationMethodFor(config: ProofConfig): String? {
        val did = DidService.load(config.issuerDid)
        val proofPurpose = config.proofPurpose ?: "assertionMethod"
        return when (proofPurpose) {
            "assertionMethod" -> did.assertionMethod?.firstOrNull { (id) -> config.issuerVerificationMethod == null || id == config.issuerVerificationMethod }
            "authentication" -> did.authentication?.firstOrNull { (id) -> config.issuerVerificationMethod == null || id == config.issuerVerificationMethod }
            "capabilityDelegation" -> did.capabilityDelegation?.firstOrNull { (id) -> config.issuerVerificationMethod == null || id == config.issuerVerificationMethod }
            "capabilityInvocation" -> did.capabilityInvocation?.firstOrNull { (id) -> config.issuerVerificationMethod == null || id == config.issuerVerificationMethod }
            "keyAgreement" -> did.keyAgreement?.firstOrNull { (id) -> config.issuerVerificationMethod == null || id == config.issuerVerificationMethod }
            else -> did.verificationMethod?.firstOrNull { (id) -> config.issuerVerificationMethod == null || id == config.issuerVerificationMethod }
        }?.id ?: config.issuerVerificationMethod
    }

    private fun fillProofConfig(config: ProofConfig): ProofConfig {
        return ProofConfig(
            issuerDid = config.issuerDid,
            subjectDid = config.subjectDid,
            null,
            issuerVerificationMethod = issuerVerificationMethodFor(config),
            proofType = config.proofType,
            domain = config.domain,
            nonce = config.nonce,
            proofPurpose = config.proofPurpose,
            credentialId = config.credentialId.orEmpty().ifEmpty { "urn:uuid:${UUID.randomUUID()}" },
            issueDate = config.issueDate ?: Instant.now(),
            validDate = config.validDate ?: Instant.now(),
            expirationDate = config.expirationDate,
            dataProviderIdentifier = config.dataProviderIdentifier,
            ldSignatureType = config.ldSignatureType ?: defaultLdSignatureByDidMethod(config.issuerDid),
            creator = config.creator
        )
    }

    override fun issue(templateIdOrFilename: String, config: ProofConfig, dataProvider: SignatoryDataProvider?, issuer: W3CIssuer?): String {

        val credentialBuilder = when(Files.exists(Path.of(templateIdOrFilename))) {
            true -> Files.readString(Path.of(templateIdOrFilename))
            else -> VcTemplateManager.getTemplate(templateIdOrFilename).template
        }.let { W3CCredentialBuilder.fromPartial(it) }

        return issue(dataProvider?.populate(credentialBuilder, config) ?: credentialBuilder, config, issuer)
    }

    override fun issue(credentialBuilder: AbstractW3CCredentialBuilder<*, *>, config: ProofConfig, issuer: W3CIssuer?): String {
        val fullProofConfig = fillProofConfig(config)
        val vcRequest = credentialBuilder.apply {
            issuer?.let { setIssuer(it) }
            setIssuerId(fullProofConfig.issuerDid)
            setIssuanceDate(fullProofConfig.issueDate ?: Instant.now())
            setIssued(fullProofConfig.issueDate ?: Instant.now())
            fullProofConfig.subjectDid?.let { setSubjectId(it) }
            setId(fullProofConfig.credentialId.orEmpty().ifEmpty { "urn:uuid:${UUID.randomUUID()}" })
            setIssuanceDate(fullProofConfig.issueDate ?: Instant.now())
            setValidFrom(fullProofConfig.validDate ?: Instant.now())
            fullProofConfig.expirationDate?.let { setExpirationDate(it) }
        }.build()

        log.info { "Signing credential with proof using ${fullProofConfig.proofType.name}..." }
        log.debug { "Signing credential with proof using ${fullProofConfig.proofType.name}, credential is: $vcRequest" }
        val signedVc = when (fullProofConfig.proofType) {
            ProofType.LD_PROOF -> JsonLdCredentialService.getService().sign(vcRequest.toJson(), fullProofConfig)
            ProofType.JWT -> JwtCredentialService.getService().sign(vcRequest.toJson(), fullProofConfig)
        }
        log.debug { "Signed VC is: $signedVc" }
        ContextManager.vcStore.storeCredential(fullProofConfig.credentialId!!, signedVc.toVerifiableCredential(), VC_GROUP)
        return signedVc
    }

    override fun listTemplates(): List<String> = VcTemplateManager.listTemplates()

    override fun loadTemplate(templateId: String): VerifiableCredential = VcTemplateManager.loadTemplate(templateId)

}
