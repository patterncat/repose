/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */

package org.openrepose.filters.samlpolicy

import java.io.{FileInputStream, StringReader}
import java.security.cert.X509Certificate
import java.security.{KeyStore, Security}
import java.util.Base64
import javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import javax.servlet.{FilterChain, FilterConfig}
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.stream.StreamSource

import net.sf.saxon.s9api.Processor
import net.shibboleth.utilities.java.support.resolver.CriteriaSet
import org.junit.runner.RunWith
import org.mockito.Mockito._
import org.mockito.{Matchers => MM}
import org.openrepose.core.services.config.ConfigurationService
import org.openrepose.core.services.serviceclient.akka.AkkaServiceClientFactory
import org.openrepose.filters.samlpolicy.config.{Cache, PolicyAcquisition, SamlPolicyConfig, SignatureCredentials}
import org.openrepose.nodeservice.atomfeed.AtomFeedService
import org.opensaml.core.config.{InitializationException, InitializationService}
import org.opensaml.core.criterion.EntityIdCriterion
import org.opensaml.saml.saml2.core.impl.ResponseUnmarshaller
import org.opensaml.security.credential.CredentialSupport
import org.opensaml.security.credential.impl.StaticCredentialResolver
import org.opensaml.xmlsec.config.JavaCryptoValidationInitializer
import org.opensaml.xmlsec.keyinfo.impl.StaticKeyInfoCredentialResolver
import org.opensaml.xmlsec.signature.SignableXMLObject
import org.opensaml.xmlsec.signature.support.impl.ExplicitKeySignatureTrustEngine
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FunSpec, Matchers}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.test.util.ReflectionTestUtils
import org.w3c.dom.Document

import scala.io.Source
import scala.xml.InputSource

/**
  * Created by adrian on 12/14/16.
  */
@RunWith(classOf[JUnitRunner])
class SamlPolicyTranslationFilterTest extends FunSpec with BeforeAndAfterEach with Matchers with MockitoSugar {

  import SamlPolicyTranslationFilterTest._

  val atomFeedService: AtomFeedService = mock[AtomFeedService]
  val signatureCredentials = new SignatureCredentials

  var filter: SamlPolicyTranslationFilter = _

  System.setProperty("javax.xml.validation.SchemaFactory:http://www.w3.org/2001/XMLSchema", "org.apache.xerces.jaxp.validation.XMLSchemaFactory")

  override def beforeEach(): Unit = {
    filter = new SamlPolicyTranslationFilter(mock[ConfigurationService], atomFeedService, mock[AkkaServiceClientFactory], CONFIG_ROOT)
    signatureCredentials.setKeystoreFilename(KEYSTORE_FILENAME)
    signatureCredentials.setKeystorePassword(KEYSTORE_PASSWORD)
    signatureCredentials.setKeyName(KEY_NAME)
    signatureCredentials.setKeyPassword(KEY_PASSWORD)
  }

  describe("doWork") {
    ignore("should call the chain") {
      val request = mock[HttpServletRequest]
      val response = mock[HttpServletResponse]
      val chain = mock[FilterChain]

      filter.doWork(request, response, chain)

      verify(chain).doFilter(request, response)
    }
  }

  describe("decodeSamlResponse") {
    it("should throw a SamlPolicyException(400) if the SAMLResponse parameter is not present") {
      val request = mock[HttpServletRequest]

      intercept[SamlPolicyException] {
        filter.decodeSamlResponse(request)
      }.statusCode shouldEqual SC_BAD_REQUEST
    }

    it("should throw a SamlPolicyException(400) if the SAMLResponse value is not Base64 encoded") {
      val request = mock[HttpServletRequest]

      when(request.getParameter("SAMLResponse"))
        .thenReturn("<samlp:Response/>")

      intercept[SamlPolicyException] {
        filter.decodeSamlResponse(request)
      }.statusCode shouldEqual SC_BAD_REQUEST
    }

    it("should return the decoded SAMLResponse") {
      val samlResponse = "<samlp:Response/>"
      val request = mock[HttpServletRequest]

      when(request.getParameter("SAMLResponse"))
        .thenReturn(Base64.getEncoder.encodeToString(samlResponse.getBytes))

      val decodedSaml = filter.decodeSamlResponse(request)

      Source.fromInputStream(decodedSaml).mkString shouldEqual samlResponse
    }
  }

  describe("readToDom") {
    pending
  }

  describe("determineVersion") {
    pending
  }

  describe("validateResponseAndGetIssuer") {
    pending
  }

  describe("getPolicy") {
    pending
  }

  describe("translateResponse") {
    val documentString =
      """
        |<saml2p:Response xmlns:saml2p="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:xs="http://www.w3.org/2001/XMLSchema"/>
      """.stripMargin
    val document = DocumentBuilderFactory.newInstance()
      .newDocumentBuilder()
      .parse(new InputSource(new StringReader(documentString)))
    val brokenXslt =
      """
        |<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
        |                version="1.0">
        |    <xsl:template match="/">
        |        <xsl:message terminate="yes">Break ALL the things!</xsl:message>
        |    </xsl:template>
        |</xsl:stylesheet>
      """.stripMargin
    val brokenXsltExec = new Processor(false).newXsltCompiler()
      .compile(new StreamSource(new StringReader(brokenXslt)))
    val workingXslt =
      """
        |<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
        |                version="1.0">
        |    <xsl:template match="/">
        |        <xsl:copy-of select="."/>
        |    </xsl:template>
        |</xsl:stylesheet>
      """.stripMargin
    val workingXsltExec = new Processor(false).newXsltCompiler()
      .compile(new StreamSource(new StringReader(workingXslt)))

    it("should throw a SamlPolicyException(400) if the translation fails") {
      intercept[SamlPolicyException] {
        filter.translateResponse(document, brokenXsltExec)
      }.statusCode shouldEqual SC_BAD_REQUEST
    }

    it("should return a translated document without throwing an exception") {
      filter.translateResponse(document, workingXsltExec) should not be document
    }
  }

  describe("signResponse") {
    SamlPolicyTranslationFilterTest.initOpenSAML()
    Seq(("server", true), ("client", false)).foreach { case (keyName, shouldPass) =>
      val passShould: Boolean => String = { boolean => if (boolean) "should" else "should not" }
      it(s"should sign the SAML Response in the HTTP Request and ${passShould(shouldPass)} validate against the $keyName key") {
        val config = new SamlPolicyConfig
        val acquisition = new PolicyAcquisition
        val cache = new Cache
        cache.setAtomFeedId("banana")
        acquisition.setCache(cache)
        config.setPolicyAcquisition(acquisition)
        config.setSignatureCredentials(signatureCredentials)
        reset(atomFeedService)

        filter.configurationUpdated(config)
        val signedDoc = filter.signResponse(SAML_RESPONSE_DOC)

        // Use the key that should have signed the DOM to create the validation criteria
        val ks = KeyStore.getInstance("JKS")
        ks.load(new FileInputStream(s"$CONFIG_ROOT/$KEYSTORE_FILENAME"), KEYSTORE_PASSWORD.toCharArray)
        val keyEntry = ks.getEntry(keyName, new KeyStore.PasswordProtection(KEY_PASSWORD.toCharArray)).asInstanceOf[KeyStore.PrivateKeyEntry]
        val signingCredential = CredentialSupport.getSimpleCredential(keyEntry.getCertificate.asInstanceOf[X509Certificate], keyEntry.getPrivateKey)
        val credResolver = new StaticCredentialResolver(signingCredential)
        val kiResolver = new StaticKeyInfoCredentialResolver(signingCredential)
        val trustEngine = new ExplicitKeySignatureTrustEngine(credResolver, kiResolver)
        val criteriaSet = new CriteriaSet(new EntityIdCriterion("urn:example.org:issuer"))

        // Extract the signed SAML Response object to ensure it was signed correctly
        val signedDocumentElement = signedDoc.getDocumentElement
        val signedXMLObject = new ResponseUnmarshaller().unmarshall(signedDocumentElement).asInstanceOf[SignableXMLObject]

        assert(signedXMLObject.isSigned)
        assert(trustEngine.validate(signedXMLObject.getSignature, criteriaSet) == shouldPass)
      }
    }
  }

  describe("convertDocumentToStream") {
    pending
  }

  describe("onNewAtomEntry") {
    pending
  }

  describe("onLifecycleEvent") {
    pending
  }

  describe("configurationUpdated") {
    var config = new SamlPolicyConfig

    def buildConfig(feedId: String, ttl: Long = 3000): SamlPolicyConfig = {
      val resultConfig = new SamlPolicyConfig
      val acquisition = new PolicyAcquisition
      val cache = new Cache
      cache.setTtl(ttl)
      cache.setAtomFeedId(feedId)
      acquisition.setCache(cache)
      resultConfig.setPolicyAcquisition(acquisition)
      resultConfig.setSignatureCredentials(signatureCredentials)
      resultConfig
    }

    def prepTest() = {
      config = buildConfig("banana")
      reset(atomFeedService)
    }

    it("should attempt to subscribe to the configured atom feed") {
      prepTest()
      filter.configurationUpdated(config)

      verify(atomFeedService).registerListener(MM.eq("banana"), MM.same(filter))
    }

    it("shouldn't try to change subscriptions when the feed didn't change") {
      prepTest()
      when(atomFeedService.registerListener(MM.eq("banana"), MM.same(filter))).thenReturn("thingy")

      filter.configurationUpdated(config)
      filter.configurationUpdated(config)

      verify(atomFeedService, times(1)).registerListener(MM.eq("banana"), MM.same(filter))
      verify(atomFeedService, never()).unregisterListener(MM.any[String])
    }

    it("should change subscription when the config changes") {
      prepTest()
      when(atomFeedService.registerListener(MM.eq("banana"), MM.same(filter))).thenReturn("thingy")
      filter.configurationUpdated(config)

      val newConfig = buildConfig("phone")

      filter.configurationUpdated(newConfig)

      verify(atomFeedService).unregisterListener("thingy")
      verify(atomFeedService).registerListener(MM.eq("phone"), MM.same(filter))
    }

    it("should unsubscribe when the feed id is removed") {
      prepTest()
      when(atomFeedService.registerListener(MM.eq("banana"), MM.same(filter))).thenReturn("thingy")
      filter.configurationUpdated(config)

      val newConfig = buildConfig(null)

      filter.configurationUpdated(newConfig)

      verify(atomFeedService).unregisterListener("thingy")
      verify(atomFeedService, times(1)).registerListener(MM.any[String], MM.same(filter))
    }

    it("should not subscribe when there is no id") {
      prepTest()
      config.getPolicyAcquisition.getCache.setAtomFeedId(null)

      filter.configurationUpdated(config)

      verifyZeroInteractions(atomFeedService)
    }

    it("should subscribe when the config changes to have an id") {
      prepTest()
      config.getPolicyAcquisition.getCache.setAtomFeedId(null)
      filter.configurationUpdated(config)
      verifyZeroInteractions(atomFeedService)

      val newConfig = buildConfig("phone")

      filter.configurationUpdated(newConfig)

      verify(atomFeedService).registerListener(MM.eq("phone"), MM.same(filter))
    }

    it("should initialize the cache when given a config") {
      ReflectionTestUtils.getField(filter, "cache") should be(null)

      filter.configurationUpdated(buildConfig("dontcare"))

      ReflectionTestUtils.getField(filter, "cache") should not be null
    }

    it("should build a new cache when the ttl changes") {
      filter.configurationUpdated(buildConfig("dontcare", 5))
      val originalCache = ReflectionTestUtils.getField(filter, "cache")

      filter.configurationUpdated(buildConfig("dontcare", 10))
      val newCache = ReflectionTestUtils.getField(filter, "cache")

      originalCache should not be theSameInstanceAs(newCache)
    }

    it("should not build a new cache if the ttl doesn't change") {
      filter.configurationUpdated(buildConfig("dontcare", 5))
      val originalCache = ReflectionTestUtils.getField(filter, "cache")

      filter.configurationUpdated(buildConfig("dontcare", 5))
      val newCache = ReflectionTestUtils.getField(filter, "cache")

      originalCache should be theSameInstanceAs newCache
    }
  }
}

object SamlPolicyTranslationFilterTest {
  val CONFIG_ROOT = "./build/resources/test/"
  val KEYSTORE_FILENAME = "single.jks"
  val KEYSTORE_PASSWORD = "password"
  val KEY_NAME = "server"
  val KEY_PASSWORD = "password"
  val SAML_RESPONSE_DOC: Document = {
    val documentBuilderFactory = DocumentBuilderFactory.newInstance()
    documentBuilderFactory.setNamespaceAware(true)
    documentBuilderFactory.newDocumentBuilder().parse(new InputSource(new StringReader(
      """<?xml version="1.0" encoding="UTF-8"?>
        |<saml2p:Response ID="_7fcd6173-e6e0-45a4-a2fd-74a4ef85bf30"
        |                 IssueInstant="2015-12-04T15:47:15.057Z"
        |                 Version="2.0"
        |                 xmlns:saml2p="urn:oasis:names:tc:SAML:2.0:protocol"
        |                 xmlns:xs="http://www.w3.org/2001/XMLSchema">
        |    <saml2:Issuer xmlns:saml2="urn:oasis:names:tc:SAML:2.0:assertion">http://test.rackspace.com</saml2:Issuer>
        |    <saml2p:Status>
        |        <saml2p:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/>
        |    </saml2p:Status>
        |    <saml2:Assertion xmlns:saml2="urn:oasis:names:tc:SAML:2.0:assertion"
        |                     xmlns:xs="http://www.w3.org/2001/XMLSchema"
        |                     ID="_406fb7fe-a519-4919-a42c-f67794a670a5"
        |                     IssueInstant="2013-11-15T16:19:06.310Z"
        |                     Version="2.0">
        |        <saml2:Issuer>http://my.rackspace.com</saml2:Issuer>
        |        <saml2:Subject>
        |            <saml2:NameID Format="urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified">john.doe</saml2:NameID>
        |            <saml2:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer">
        |                <saml2:SubjectConfirmationData NotOnOrAfter="2113-11-17T16:19:06.298Z"/>
        |            </saml2:SubjectConfirmation>
        |        </saml2:Subject>
        |        <saml2:AuthnStatement AuthnInstant="2113-11-15T16:19:04.055Z">
        |            <saml2:AuthnContext>
        |                <saml2:AuthnContextClassRef>
        |                    urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport
        |                </saml2:AuthnContextClassRef>
        |            </saml2:AuthnContext>
        |        </saml2:AuthnStatement>
        |        <saml2:AttributeStatement>
        |            <saml2:Attribute Name="roles">
        |                <saml2:AttributeValue xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="xs:string">
        |                    nova:admin
        |                </saml2:AttributeValue>
        |            </saml2:Attribute>
        |            <saml2:Attribute Name="domain">
        |                <saml2:AttributeValue xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="xs:string">
        |                    323676
        |                </saml2:AttributeValue>
        |            </saml2:Attribute>
        |            <saml2:Attribute Name="email">
        |                <saml2:AttributeValue xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="xs:string">
        |                    no-reply@rackspace.com
        |                </saml2:AttributeValue>
        |            </saml2:Attribute>
        |            <saml2:Attribute Name="FirstName">
        |                <saml2:AttributeValue xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="xs:string">
        |                    John
        |                </saml2:AttributeValue>
        |            </saml2:Attribute>
        |            <saml2:Attribute Name="LastName">
        |                <saml2:AttributeValue xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="xs:string">
        |                    Doe
        |                </saml2:AttributeValue>
        |            </saml2:Attribute>
        |        </saml2:AttributeStatement>
        |    </saml2:Assertion>
        |</saml2p:Response>
        |""".stripMargin)))
  }

  private val LOG: Logger = LoggerFactory.getLogger(classOf[SamlPolicyTranslationFilterTest].getName)

  def initOpenSAML(): Unit = {
    // Adapted from: A Guide to OpenSAML V3 by Stefan Rasmusson pg 32
    try {
      val javaCryptoValidationInitializer = new JavaCryptoValidationInitializer
      javaCryptoValidationInitializer.init()
      for (jceProvider <- Security.getProviders) {
        LOG.trace(jceProvider.getInfo)
      }
      InitializationService.initialize()
    } catch {
      case e: InitializationException => new RuntimeException("Initialization failed", e)
    }
  }
}
