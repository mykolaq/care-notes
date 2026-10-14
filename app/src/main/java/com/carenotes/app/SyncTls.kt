package com.carenotes.app

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

internal object SyncTls {
    fun parse(bytes: ByteArray): X509Certificate {
        require(bytes.isNotEmpty() && bytes.size <= 16384) { "Сертификат слишком большой" }
        val certificates = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes))
        require(certificates.size == 1) { "Выберите один корневой сертификат сервера" }
        val certificate = certificates.single() as X509Certificate
        require(certificate.basicConstraints >= 0) { "Нужен корневой сертификат центра сертификации" }
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
        return certificate
    }
    fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(parse(bytes).encoded).joinToString(":") { "%02X".format(it) }
    fun socketFactory(bytes: ByteArray): SSLSocketFactory {
        val certificate = parse(bytes)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("care-server", certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }
}
