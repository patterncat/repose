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
package org.openrepose.filters.valkyrieauthorization

import java.io.ByteArrayInputStream
import java.net.URL
import java.nio.charset.{Charset, StandardCharsets}
import java.util
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletResponse._
import javax.servlet.http.{HttpServletRequestWrapper, HttpServletResponse, HttpServletResponseWrapper}
import javax.servlet.{FilterChain, ServletRequest, ServletResponse}

import com.mockrunner.mock.web.{MockFilterChain, MockFilterConfig, MockHttpServletRequest, MockHttpServletResponse}
import com.rackspace.httpdelegation.{HttpDelegationHeaderNames, HttpDelegationManager}
import org.apache.http.message.BasicHeader
import org.apache.http.{Header, HttpHeaders}
import org.junit.runner.RunWith
import org.mockito.AdditionalMatchers._
import org.mockito.Matchers._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{ArgumentCaptor, Matchers, Mockito}
import org.openrepose.commons.utils.http.normal.ExtendedStatusCodes.SC_TOO_MANY_REQUESTS
import org.openrepose.commons.utils.http.{CommonHttpHeader, ServiceClientResponse}
import org.openrepose.core.services.config.ConfigurationService
import org.openrepose.core.services.datastore.{Datastore, DatastoreService}
import org.openrepose.core.services.serviceclient.akka.{AkkaServiceClient, AkkaServiceClientException, AkkaServiceClientFactory}
import org.openrepose.filters.valkyrieauthorization.config.DevicePath.Regex
import org.openrepose.filters.valkyrieauthorization.config.HttpMethod._
import org.openrepose.filters.valkyrieauthorization.config._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FunSpec, Matchers => ScalaTestMatchers}
import org.springframework.mock.web.{MockHttpServletResponse => SpringHttpServletResponse}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class ValkyrieAuthorizationFilterTest extends FunSpec with BeforeAndAfterEach with MockitoSugar
  with HttpDelegationManager with ScalaTestMatchers {
  private final val CACHE_PREFIX = "VALKYRIE-FILTER"

  //todo: I suspect some of these tests are repetitive now, although they test it from different perspectives so
  // probably still worthwhile. I think some describe mocking behavior where it's not necessary as well. Short
  // timelines mean i can't dig into them right now.
  val akkaServiceClient = mock[AkkaServiceClient]
  val akkaServiceClientFactory = mock[AkkaServiceClientFactory]
  val mockDatastoreService = mock[DatastoreService]
  val mockDatastore: Datastore = mock[Datastore]
  Mockito.when(mockDatastoreService.getDefaultDatastore).thenReturn(mockDatastore)

  override def beforeEach() = {
    Mockito.reset(mockDatastore)
    Mockito.reset(akkaServiceClient)
    Mockito.reset(akkaServiceClientFactory)

    Mockito.when(akkaServiceClientFactory.newAkkaServiceClient(or(anyString(), isNull.asInstanceOf[String]))).thenReturn(akkaServiceClient)
  }

  describe("when initializing the filter") {
    it("should initialize the configuration to a given configuration") {
      val mockConfigService = mock[ConfigurationService]
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mockConfigService, akkaServiceClientFactory, mockDatastoreService)

      val config: MockFilterConfig = new MockFilterConfig
      config.setFilterName("ValkyrieFilter")

      filter.init(config)

      val resourceCaptor = ArgumentCaptor.forClass(classOf[URL])
      Mockito.verify(mockConfigService).subscribeTo(
        Matchers.eq("ValkyrieFilter"),
        Matchers.eq("valkyrie-authorization.cfg.xml"),
        resourceCaptor.capture,
        Matchers.eq(filter),
        Matchers.eq(classOf[ValkyrieAuthorizationConfig]))

      assert(resourceCaptor.getValue.toString.endsWith("/META-INF/schema/config/valkyrie-authorization.xsd"))
    }

    it("should initialize the configuration to a given name") {
      val mockConfigService = mock[ConfigurationService]
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mockConfigService, akkaServiceClientFactory, mockDatastoreService)

      val config: MockFilterConfig = new MockFilterConfig
      config.setInitParameter("filter-config", "another-name.cfg.xml")

      filter.init(config)

      Mockito.verify(mockConfigService).subscribeTo(
        Matchers.anyString,
        Matchers.eq("another-name.cfg.xml"),
        Matchers.any(classOf[URL]),
        Matchers.any(classOf[ValkyrieAuthorizationFilter]),
        Matchers.eq(classOf[ValkyrieAuthorizationConfig]))
    }
  }

  describe("when destroying the filter") {
    it("should deregister the configuration from the configuration service") {
      val mockConfigService = mock[ConfigurationService]
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mockConfigService, akkaServiceClientFactory, mockDatastoreService)

      val config: MockFilterConfig = new MockFilterConfig
      filter.init(config)
      filter.destroy

      Mockito.verify(mockConfigService).unsubscribeFrom("valkyrie-authorization.cfg.xml", filter)
    }

    it("should destroy the akka service client") {
      val mockConfigService = mock[ConfigurationService]
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mockConfigService, akkaServiceClientFactory, mockDatastoreService)

      val config: MockFilterConfig = new MockFilterConfig
      filter.init(config)
      filter.configurationUpdated(new ValkyrieAuthorizationConfig)
      filter.destroy

      Mockito.verify(akkaServiceClient).destroy()
    }
  }

  describe("when the configuration is updated") {
    it("should set the current configuration on the filter with the defaults initially and flag that it is initialized") {
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)

      assert(!filter.isInitialized)

      val configuration = new ValkyrieAuthorizationConfig
      filter.configurationUpdated(configuration)

      assert(filter.configuration == configuration)
      assert(filter.configuration.getDelegating == null)
      assert(filter.configuration.getCacheTimeoutMillis == 300000)
      assert(filter.isInitialized)
    }

    it("should set the default delegation quality to .1") {
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)

      assert(filter.configuration == null)

      val configuration = new ValkyrieAuthorizationConfig
      val delegation = new DelegatingType
      configuration.setDelegating(delegation)
      filter.configurationUpdated(configuration)

      assert(filter.configuration.getDelegating.getQuality == .1)
    }

    it("should set the configuration to current and update the cache timeout") {
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)

      val configuration = new ValkyrieAuthorizationConfig
      filter.configurationUpdated(configuration)

      assert(filter.configuration == configuration)
      assert(filter.isInitialized)

      val newConfiguration = new ValkyrieAuthorizationConfig
      filter.configurationUpdated(newConfiguration)

      assert(filter.configuration == newConfiguration)
      assert(filter.isInitialized)
    }

    it("should destroy the previous akka service client if one already existed") {
      val firstAkkaServiceClient = mock[AkkaServiceClient]
      val secondAkkaServiceClient = mock[AkkaServiceClient]
      Mockito.when(akkaServiceClientFactory.newAkkaServiceClient(or(anyString(), isNull.asInstanceOf[String])))
        .thenReturn(firstAkkaServiceClient)
        .thenReturn(secondAkkaServiceClient)

      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)

      val configuration = new ValkyrieAuthorizationConfig
      filter.configurationUpdated(configuration)
      filter.configurationUpdated(configuration)

      Mockito.verify(akkaServiceClientFactory, Mockito.times(2)).newAkkaServiceClient(or(anyString(), isNull.asInstanceOf[String]))
      Mockito.verify(firstAkkaServiceClient, Mockito.times(1)).destroy()
      Mockito.verify(secondAkkaServiceClient, Mockito.never()).destroy()
    }
  }

  describe("when a request to authorize occurs") {
    case class RequestProcessor(method: String, headers: Map[String, String], url: String = "http://foo.com:8080")
    case class ValkyrieResponse(code: Int, payload: String)
    case class Result(code: Int, message: String)

    List((RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "view_product")))), //View role
      (RequestProcessor("HEAD", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "view_product")))), //Without colon in tenant
      (RequestProcessor("POST", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "edit_product")))), //Edit role
      (RequestProcessor("PUT", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "admin_product")))), //Admin role
      (RequestProcessor("PUT", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "98765", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_OK, createValkyrieResponse(accountPermissions("account_admin", "butts_permission"), devicePermissions("123456", "admin_product")))) //account Admin role
    ).foreach { case (request, valkyrie) =>
      it(s"should allow requests for $request with Valkyrie response of $valkyrie") {
        setMockAkkaBehavior("someTenant", request.headers.getOrElse("X-Contact-Id", "ThisIsMissingAContact"), valkyrie.code, valkyrie.payload)

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(createGenericValkyrieConfiguration(null))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod(request.method)
        mockServletRequest.setRequestURL(request.url)
        request.headers.foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

        val mockFilterChain = mock[FilterChain]
        filter.doFilter(mockServletRequest, new MockHttpServletResponse, mockFilterChain)

        val responseCaptor = ArgumentCaptor.forClass(classOf[HttpServletResponseWrapper])
        Mockito.verify(mockFilterChain).doFilter(Matchers.any(classOf[ServletRequest]), responseCaptor.capture())
        assert(responseCaptor.getValue.getStatus == SC_OK)
      }
    }

    List((RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Contact-Id" -> "123456"), "http://foo.com:8080/foo"), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "view_product")))), //View role
      (RequestProcessor("HEAD", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Contact-Id" -> "123456"), "http://foo.com:8080/foo"), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "view_product")))), //Without colon in tenant
      (RequestProcessor("POST", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Contact-Id" -> "123456"), "http://foo.com:8080/foo"), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "edit_product")))), //Edit role
      (RequestProcessor("PUT", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Contact-Id" -> "123456"), "http://foo.com:8080/foo"), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "admin_product")))), //Admin role
      (RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Contact-Id" -> "123456"), "http://foo.com:8080/bar"), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "view_product")))), //View role
      (RequestProcessor("HEAD", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Contact-Id" -> "123456"), "http://foo.com:8080/bar"), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "view_product")))), //Without colon in tenant
      (RequestProcessor("POST", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Contact-Id" -> "123456"), "http://foo.com:8080/bar"), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "edit_product")))), //Edit role
      (RequestProcessor("PUT", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Contact-Id" -> "123456"), "http://foo.com:8080/bar"), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "admin_product")))) //Admin role
    ).foreach { case (request, valkyrie) =>
      it(s"should allow requests for $request with Valkyrie response of $valkyrie without device id when on either accepted list") {
        setMockAkkaBehavior("someTenant", request.headers.getOrElse("X-Contact-Id", "ThisIsMissingAContact"), valkyrie.code, valkyrie.payload)

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(createGenericValkyrieConfiguration(null))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod(request.method)
        mockServletRequest.setRequestURL(request.url)
        request.headers.foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

        val mockFilterChain = mock[FilterChain]
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            invocation.getArguments()(1).asInstanceOf[HttpServletResponse].getOutputStream.print(createOriginServiceResponse("98765", "123456"))
        })

        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        assert(originalResponse.getStatus == SC_OK)
      }
    }

    List((RequestProcessor("GET", Map("X-Tenant-Id" -> "application:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("111111", "view_product"))), Result(SC_FORBIDDEN, "Not Authorized")), //Not a hybrid tenant
      (RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("111111", "view_product"))), Result(SC_FORBIDDEN, "Not Authorized")), //Non matching device
      (RequestProcessor("PUT", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "view_product"))), Result(SC_FORBIDDEN, "Not Authorized")), //Non matching role
      (RequestProcessor("PUT", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("123456", "not_a_role"))), Result(SC_FORBIDDEN, "Not Authorized")), //Not a real role
      (RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_FORBIDDEN, ""), Result(SC_INTERNAL_SERVER_ERROR, "Valkyrie said the credentials were forbidden")), //Bad Permissions to Valkyrie
      (RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456")), ValkyrieResponse(SC_NOT_FOUND, ""), Result(SC_UNAUTHORIZED, "No contact ID specified")), //Missing Contact
      (RequestProcessor("GET", Map("X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_NOT_FOUND, ""), Result(SC_UNAUTHORIZED, "No tenant ID specified")), //Missing Tenant
      (RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_OK, createValkyrieResponse(devicePermissions("", "view_product"))), Result(SC_BAD_GATEWAY, "Invalid Json response from Valkyrie")), //Malformed Valkyrie Response - Missing Device
      (RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(SC_OK, "I'm not really json"), Result(SC_BAD_GATEWAY, "Invalid Json response from Valkyrie")) //Malformed Valkyrie Response - Bad Json
    ).foreach { case (request, valkyrie, result) =>
      List(null, new DelegatingType).foreach { delegation =>
        val delegating = Option(delegation).isDefined
        it(s"should be ${result.code} where delegation is $delegating for $request with Valkyrie response of $valkyrie") {
          setMockAkkaBehavior("someTenant", request.headers.getOrElse("X-Contact-Id", "ThisIsMissingAContact"), valkyrie.code, valkyrie.payload)

          val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
          filter.configurationUpdated(createGenericValkyrieConfiguration(delegation))

          val mockServletRequest = new MockHttpServletRequest
          mockServletRequest.setMethod(request.method)
          mockServletRequest.setRequestURL(request.url)
          request.headers.foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

          val mockServletResponse = new MockHttpServletResponse
          val mockFilterChain = mock[FilterChain]
          filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)

          if (Option(delegation).isDefined) {
            assert(mockServletResponse.getStatusCode == SC_OK)
            val requestCaptor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])
            Mockito.verify(mockFilterChain).doFilter(requestCaptor.capture(), Matchers.any(classOf[ServletResponse]))
            val delegationHeaders: Map[String, List[String]] = buildDelegationHeaders(result.code, "valkyrie-authorization", result.message, .1)
            assert(requestCaptor.getValue.getHeaders(HttpDelegationHeaderNames.Delegated).toList == delegationHeaders.get(HttpDelegationHeaderNames.Delegated).get)
          } else {
            assert(mockServletResponse.getStatusCode == result.code)
          }
        }
      }
    }

    List(null, new DelegatingType).foreach { delegation =>
      val delegating = Option(delegation).isDefined
      it(s"should return a 502 and delegation is $delegating with appropriate message when unable to communicate with Valkyrie") {
        Mockito.when(akkaServiceClient.get(Matchers.any(), Matchers.any(), Matchers.any())).thenThrow(new AkkaServiceClientException("Valkyrie is missing", new Exception()))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(createGenericValkyrieConfiguration(delegation))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com:8080")
        Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456").foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

        val mockFilterChain = mock[FilterChain]
        val mockServletResponse = new MockHttpServletResponse
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)

        if (Option(delegation).isDefined) {
          assert(mockServletResponse.getStatusCode == SC_OK)
          val requestCaptor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])
          Mockito.verify(mockFilterChain).doFilter(requestCaptor.capture(), Matchers.any(classOf[ServletResponse]))
          val delegationHeaders: Map[String, List[String]] = buildDelegationHeaders(SC_BAD_GATEWAY, "valkyrie-authorization", "Unable to communicate with Valkyrie: Valkyrie is missing", .1)
          assert(requestCaptor.getValue.getHeaders(HttpDelegationHeaderNames.Delegated).toList == delegationHeaders.get(HttpDelegationHeaderNames.Delegated).get)
        } else {
          assert(mockServletResponse.getStatusCode == SC_BAD_GATEWAY)
        }
      }
    }

    it("should bypasses validation if the user has a role listed in pre-authorized-roles") {
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)

      val configuration = createGenericValkyrieConfiguration(null)
      val preAuthorizedRoles: RolesList = new RolesList
      val roles: util.List[String] = preAuthorizedRoles.getRole
      val superRootAdminUser = "superRootAdminUser"
      roles.add(superRootAdminUser)
      configuration.setPreAuthorizedRoles(preAuthorizedRoles)

      filter.configurationUpdated(configuration)

      val mockServletRequest = new MockHttpServletRequest
      mockServletRequest.setMethod("GET")
      mockServletRequest.setRequestURL("http://foo.com:8080")
      mockServletRequest.setHeader("X-Roles", s"$superRootAdminUser,buttsRole")
      val mockServletResponse = new MockHttpServletResponse
      val mockFilterChain = mock[FilterChain]

      filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)

      assert(mockServletResponse.getStatusCode == SC_OK)
    }

    it("should be able to cache the valkyrie permissions so we dont have to make repeated calls") {
      val request = RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "1234561", "X-Contact-Id" -> "123456"))
      setMockAkkaBehavior("someTenant",
        request.headers.getOrElse("X-Contact-Id", "ThisIsMissingAContact"),
        SC_OK,
        createValkyrieResponse(devicePermissions("123456", "view_product")))

      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
      Mockito.when(mockDatastore.get("VALKYRIE-FILTERanysomeTenant123456")).thenAnswer(new Answer[Serializable] {
        var firstAttempt = true

        override def answer(invocation: InvocationOnMock): Serializable =
          if (firstAttempt) {
            firstAttempt = false
            null
          } else {
            filter.UserPermissions(Vector.empty[String], Vector(filter.DeviceToPermission(123456, "view_product"), filter.DeviceToPermission(1234561, "view_product1"))).asInstanceOf[Serializable]
          }
      })
      filter.configurationUpdated(createGenericValkyrieConfiguration(null))

      val mockServletRequest = new MockHttpServletRequest
      mockServletRequest.setMethod(request.method)
      mockServletRequest.setRequestURL("http://foo.com:8080")
      request.headers.foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

      val mockServletResponse = new MockHttpServletResponse
      val mockFilterChain = mock[FilterChain]
      filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)
      assert(mockServletResponse.getStatusCode == SC_FORBIDDEN)

      Mockito.verify(mockDatastore).put("VALKYRIE-FILTERanysomeTenant123456", filter.UserPermissions(Vector.empty[String], Vector(filter.DeviceToPermission(1234561, "view_product1"), filter.DeviceToPermission(123456, "view_product"))), 300000, TimeUnit.MILLISECONDS)

      val secondRequest = new MockHttpServletRequest
      val secondServletResponse = new MockHttpServletResponse
      val secondRequestProcessor = RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456"))
      secondRequest.setMethod(secondRequestProcessor.method)
      secondRequest.setRequestURL("http://foo.com:8080")
      secondRequestProcessor.headers.foreach { case (k, v) => secondRequest.setHeader(k, v) }
      filter.doFilter(secondRequest, secondServletResponse, mockFilterChain)
      assert(secondServletResponse.getStatusCode == SC_OK)

      Mockito.verify(akkaServiceClient, Mockito.times(1)).get(
        "VALKYRIE-FILTERanysomeTenant" + request.headers.get("X-Contact-Id").get,
        s"http://foo.com:8080/account/someTenant/permissions/contacts/any/by_contact/${request.headers.get("X-Contact-Id").get}/effective",
        Map("X-Auth-User" -> "someUser", "X-Auth-Token" -> "somePassword"))
    }

    List(null, new DelegatingType).foreach { delegation =>
      it(s"should be able to mask 403 to a 404 ${
        if (delegation != null) {
          "using delegation"
        } else {
          ""
        }
      } ") {
        val request = RequestProcessor("PUT", Map("X-Tenant-Id" -> "application:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456"))

        setMockAkkaBehavior("someTenant", request.headers.getOrElse("X-Contact-Id", "123456"), SC_OK, createValkyrieResponse(devicePermissions("123456", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        val configuration: ValkyrieAuthorizationConfig = createGenericValkyrieConfiguration(delegation)
        configuration.setEnableMasking403S(true)
        filter.configurationUpdated(configuration)

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod(request.method)
        mockServletRequest.setRequestURL("http://foo.com:8080")
        request.headers.foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

        val mockServletResponse = new MockHttpServletResponse
        val mockFilterChain = mock[FilterChain]
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)

        if (Option(delegation).isDefined) {
          assert(mockServletResponse.getStatusCode == SC_OK)
          val requestCaptor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])
          Mockito.verify(mockFilterChain).doFilter(requestCaptor.capture(), Matchers.any(classOf[ServletResponse]))
          val delegationHeaders: Map[String, List[String]] = buildDelegationHeaders(SC_NOT_FOUND, "valkyrie-authorization", "Not Found", .1)
          assert(requestCaptor.getValue.getHeaders(HttpDelegationHeaderNames.Delegated).toList == delegationHeaders.get(HttpDelegationHeaderNames.Delegated).get)
        } else {
          assert(mockServletResponse.getStatusCode == SC_NOT_FOUND)
        }
      }
    }

    describe("when user has the account_admin role") {
      val deviceId = "56700"

      List(
        (true, "12345", "12345", SC_OK), // account_admin bypass
        (true, deviceId, "12345", SC_OK), // device Id in permissions from effective call
        (true, "12345", deviceId, SC_OK), // device Id in permissions from inventory call
        (false, "12345", "12345", SC_FORBIDDEN), // not authorized for device Id
        (false, deviceId, "12345", SC_OK), // device Id in permissions from effective call
        (false, "12345", deviceId, SC_OK), // device Id in permissions from inventory call
        (false, deviceId, deviceId, SC_OK) // device Id in permissions from both calls
      ).foreach { case (enableBypassAccountAdmin, deviceIdInEffective, deviceIdInInventory, responseCode) =>
        it(s"should return $responseCode when enable_bypass_account_admin is $enableBypassAccountAdmin, effective call perm has device id $deviceIdInEffective, inventory call perm has device id $deviceIdInInventory, and request device id is $deviceId") {
          setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(accountPermissions("account_admin", "butts_permission"), devicePermissions(deviceIdInEffective, "admin_product")))
          setAdminAkkaBehavior("someTenant", "123456", SC_OK, accountInventory(deviceIdInInventory, "10001"))

          val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
          filter.configurationUpdated(createGenericValkyrieConfiguration(null, enableBypassAccountAdmin))

          val mockServletRequest = new MockHttpServletRequest
          val request = RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> deviceId, "X-Contact-Id" -> "123456"))
          mockServletRequest.setMethod(request.method)
          mockServletRequest.setRequestURL(request.url)
          request.headers.foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

          val mockServletResponse = new MockHttpServletResponse
          val mockFilterChain = mock[FilterChain]

          filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)

          assert(mockServletResponse.getStatusCode == responseCode)
        }
      }
    }

    it("should return a failure if the inventory call fails") {
      setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(accountPermissions("account_admin", "butts_permission"), devicePermissions("12345", "admin_product")))
      setAdminAkkaBehavior("someTenant", "123456", SC_INTERNAL_SERVER_ERROR, "")

      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
      filter.configurationUpdated(createGenericValkyrieConfiguration(null, false))

      val mockServletRequest = new MockHttpServletRequest
      val request = RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "12345", "X-Contact-Id" -> "123456"))
      mockServletRequest.setMethod(request.method)
      mockServletRequest.setRequestURL(request.url)
      request.headers.foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

      val mockServletResponse = new MockHttpServletResponse
      val mockFilterChain = mock[FilterChain]

      filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)

      assert(mockServletResponse.getStatusCode == SC_BAD_GATEWAY)
    }

    it("should send a request guid to valkyrie if present in incoming request") {
      val request = RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456",
        "X-Contact-Id" -> "123456", CommonHttpHeader.TRACE_GUID.toString -> "test-guid"))
      Mockito.when(akkaServiceClient.get(
        "VALKYRIE-FILTERanysomeTenant123456",
        "http://foo.com:8080/account/someTenant/permissions/contacts/any/by_contact/123456/effective",
        Map("X-Auth-User" -> "someUser", "X-Auth-Token" -> "somePassword", CommonHttpHeader.TRACE_GUID.toString -> "test-guid")))
        .thenReturn(new ServiceClientResponse(SC_OK, new ByteArrayInputStream(createValkyrieResponse(devicePermissions("123456", "view_product")).getBytes)))

      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
      filter.configurationUpdated(createGenericValkyrieConfiguration(null))

      val mockServletRequest = new MockHttpServletRequest
      mockServletRequest.setMethod(request.method)
      mockServletRequest.setRequestURL("http://foo.com:8080")
      request.headers.foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

      val mockFilterChain = mock[FilterChain]
      filter.doFilter(mockServletRequest, new MockHttpServletResponse, mockFilterChain)

      Mockito.verify(akkaServiceClient).get("VALKYRIE-FILTERanysomeTenant123456",
        "http://foo.com:8080/account/someTenant/permissions/contacts/any/by_contact/123456/effective",
        Map("X-Auth-User" -> "someUser", "X-Auth-Token" -> "somePassword", CommonHttpHeader.TRACE_GUID.toString -> "test-guid"))
    }
  }

  describe("when permission to role translation is turned on and request includes a Device ID") {
    val config = createGenericValkyrieConfiguration(null)
    config.setTranslatePermissionsToRoles(new Object)
    val tenantId = "hybrid:98765"
    val transformedTenant = "98765"
    val contactId = "123456"
    val deviceId = "98765"
    val filterChain = mock[FilterChain]
    val mockServletRequest = new MockHttpServletRequest
    val mockServletResponse = new MockHttpServletResponse
    Mockito.when(akkaServiceClientFactory.newAkkaServiceClient(or(anyString(), isNull.asInstanceOf[String]))).thenReturn(akkaServiceClient)
    val filter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
    filter.configurationUpdated(config)

    def setup() = {
      mockServletRequest.resetAll()
      mockServletRequest.setMethod("GET")
      mockServletRequest.setRequestURL("http://foo.com:8080")
      mockServletRequest.setHeader("X-Tenant-Id", tenantId)
      mockServletRequest.setHeader("X-Contact-Id", contactId)

      mockServletResponse.resetAll()

      Mockito.reset(filterChain)
    }

    List("view_product", "edit_product", "admin_product").foreach { devicePermission =>
      it(s"should translate permissions to roles with device permission $devicePermission") {
        setup()
        val devices = devicePermissions(deviceId, devicePermission)
        mockServletRequest.setHeader("X-Device-Id", deviceId)
        setMockAkkaBehavior(transformedTenant, contactId, SC_OK,
          createValkyrieResponse(accountPermissions("some_permission", "a_different_permission"), devices))
        val captor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])

        filter.doFilter(mockServletRequest, mockServletResponse, filterChain)

        Mockito.verify(filterChain).doFilter(captor.capture(), Matchers.any(classOf[ServletResponse]))
        val roles = captor.getValue.getHeaders("X-Roles").asScala.toList
        assert(roles.contains("some_permission"))
        assert(roles.contains("a_different_permission"))
        assert(roles.contains(devicePermission))
      }
    }

    List("remember_product", "consider_product", "admire_product").foreach { devicePermission =>
      it(s"should not be authorized to translate permissions to roles with device permission $devicePermission") {
        setup()
        val devices = devicePermissions(deviceId, devicePermission)
        mockServletRequest.setHeader("X-Device-Id", deviceId)
        setMockAkkaBehavior(transformedTenant, contactId, SC_OK,
          createValkyrieResponse(accountPermissions("some_permission", "a_different_permission"), devices))

        filter.doFilter(mockServletRequest, mockServletResponse, filterChain)

        assert(mockServletResponse.getStatusCode == SC_FORBIDDEN)
      }
    }
  }

  describe("when permission to role translation is turned on") {
    val config = createGenericValkyrieConfiguration(null)
    config.setTranslatePermissionsToRoles(new Object)
    val tenantId = "hybrid:98765"
    val transformedTenant = "98765"
    val contactId = "123456"
    val filterChain = mock[FilterChain]
    val mockServletRequest = new MockHttpServletRequest
    val mockServletResponse = new MockHttpServletResponse
    val devices = devicePermissions("98765", "view_product")
    val filter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
    filter.configurationUpdated(config)

    def setup() = {
      mockServletRequest.resetAll()
      mockServletRequest.setMethod("GET")
      mockServletRequest.setRequestURL("http://foo.com:8080")
      mockServletRequest.setHeader("X-Tenant-Id", tenantId)
      mockServletRequest.setHeader("X-Contact-Id", contactId)

      mockServletResponse.resetAll()

      Mockito.reset(filterChain)
    }

    it("should translate permissions to roles") {
      setup()
      setMockAkkaBehavior(transformedTenant, contactId, SC_OK, createValkyrieResponse(accountPermissions("some_permission", "a_different_permission"), devices))
      val captor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)

      Mockito.verify(filterChain).doFilter(captor.capture(), Matchers.any(classOf[ServletResponse]))
      val roles = captor.getValue.getHeaders("X-Roles").asScala.toList
      assert(roles.contains("a_different_permission"))
      assert(roles.contains("some_permission"))
    }

    it("should 401 when tenant id isn't present") {
      setup()
      mockServletRequest.clearHeaders()
      mockServletRequest.setHeader("X-Contact-Id", contactId)
      setMockAkkaBehavior(transformedTenant, contactId, SC_OK, createValkyrieResponse(accountPermissions("some_permission", "a_different_permission"), devices))

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)

      assert(mockServletResponse.getStatus == SC_UNAUTHORIZED)
    }

    it("should 401 when contact id isn't present") {
      setup()
      mockServletRequest.clearHeaders()
      mockServletRequest.setHeader("X-Tenant-Id", tenantId)
      setMockAkkaBehavior(transformedTenant, contactId, SC_OK, createValkyrieResponse(accountPermissions("some_permission", "a_different_permission"), devices))

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)

      assert(mockServletResponse.getStatus == SC_UNAUTHORIZED)
    }

    it("should 403 when tenant is non-hybrid") {
      setup()
      mockServletRequest.clearHeaders()
      mockServletRequest.setHeader("X-Tenant-Id", "987654")
      mockServletRequest.setHeader("X-Contact-Id", contactId)
      setMockAkkaBehavior(transformedTenant, contactId, SC_OK, createValkyrieResponse(accountPermissions("some_permission", "a_different_permission"), devices))

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)

      assert(mockServletResponse.getStatus == SC_FORBIDDEN)
    }

    it("should 502 when valkyrie 404s") {
      setup()
      setMockAkkaBehavior(transformedTenant, contactId, SC_NOT_FOUND, "Not found")

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)

      assert(mockServletResponse.getStatus == SC_BAD_GATEWAY)
    }

    it("should 502 when valkyrie 500s") {
      setup()
      setMockAkkaBehavior(transformedTenant, contactId, SC_INTERNAL_SERVER_ERROR, "Internal Server Error")

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)

      assert(mockServletResponse.getStatus == SC_BAD_GATEWAY)
    }

    it("should 502 when valkyrie gives an unexpected response") {
      setup()
      setMockAkkaBehavior(transformedTenant, contactId, SC_OK, """{"banana":"phone"}""")

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)

      assert(mockServletResponse.getStatus == SC_BAD_GATEWAY)
    }

    it("should 502 when we have an exception while talking to valkyrie") {
      setup()
      Mockito.when(akkaServiceClient.get(Matchers.any(classOf[String]),
        Matchers.eq(s"http://foo.com:8080/account/$transformedTenant/permissions/contacts/any/by_contact/$contactId/effective"),
        Matchers.any(classOf[java.util.Map[String, String]]))).thenThrow(new AkkaServiceClientException("test exception", null))

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)

      assert(mockServletResponse.getStatus == SC_BAD_GATEWAY)
    }

    it("should use the values from the datastore when available") {
      setup()
      Mockito.when(mockDatastore.get(CACHE_PREFIX + "any" + transformedTenant + contactId))
        .thenReturn(filter.UserPermissions(Vector("some_permission", "a_different_permission"), Vector.empty[filter.DeviceToPermission]), Nil: _*)
      val captor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)
      Mockito.verify(filterChain).doFilter(captor.capture(), Matchers.any(classOf[ServletResponse]))

      val roles = captor.getValue.getHeaders("X-Roles").asScala.toList
      assert(roles.contains("some_permission"))
      assert(roles.contains("a_different_permission"))

    }
  }

  describe("when pass-non-dedicated-tenant is enabled") {
    val config = createGenericValkyrieConfiguration(passNonDedicatedTenant = true)
    val filter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
    filter.configurationUpdated(config)

    it("should pass the request when tenant is non-hybrid") {
      val mockServletRequest = new MockHttpServletRequest()
      val mockServletResponse = new MockHttpServletResponse()
      val mockFilterChain = new MockFilterChain()

      mockServletRequest.setMethod("GET")
      mockServletRequest.setRequestURL("http://foo.com:8080")
      mockServletRequest.setHeader("X-Tenant-Id", "987654")
      mockServletRequest.setHeader("X-Contact-Id", "12345")

      filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)

      mockFilterChain.getLastRequest should not be null
    }
  }

  describe("when permission to role translation and delegation is turned on") {
    val config = createGenericValkyrieConfiguration(null)
    config.setTranslatePermissionsToRoles(new Object)
    config.setDelegating(new DelegatingType)
    config.setCollectionResources(null)
    val tenantId = "hybrid:98765"
    val transformedTenant = "98765"
    val contactId = "123456"
    val filterChain = mock[FilterChain]
    val mockServletRequest = new MockHttpServletRequest
    val mockServletResponse = new MockHttpServletResponse
    val filter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
    filter.configurationUpdated(config)

    def setup() = {
      mockServletRequest.resetAll()
      mockServletRequest.setMethod("GET")
      mockServletRequest.setRequestURL("http://foo.com:8080")
      mockServletRequest.setHeader("X-Tenant-Id", tenantId)
      mockServletRequest.setHeader("X-Contact-Id", contactId)

      mockServletResponse.resetAll()

      Mockito.reset(filterChain)
    }

    it("should translate permissions to roles") {
      setup()
      setMockAkkaBehavior(transformedTenant, contactId, SC_OK, createValkyrieResponse(accountPermissions("some_permission", "a_different_permission")))
      val captor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)

      Mockito.verify(filterChain).doFilter(captor.capture(), Matchers.any(classOf[ServletResponse]))
      val roles = captor.getValue.getHeaders("X-Roles").asScala.toList
      assert(roles.contains("a_different_permission"))
      assert(roles.contains("some_permission"))
    }

    it("should 401 when tenant id isn't present") {
      setup()
      mockServletRequest.clearHeaders()
      mockServletRequest.setHeader("X-Contact-Id", contactId)
      setMockAkkaBehavior(transformedTenant, contactId, SC_OK, createValkyrieResponse(accountPermissions("some_permission", "a_different_permission")))
      val captor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)
      Mockito.verify(filterChain).doFilter(captor.capture(), Matchers.any(classOf[ServletResponse]))

      assert(mockServletResponse.getStatus == SC_OK)
      assert(captor.getValue.getHeader(HttpDelegationHeaderNames.Delegated).contains("401"))
    }

    it("should 401 when contact id isn't present") {
      setup()
      mockServletRequest.clearHeaders()
      mockServletRequest.setHeader("X-Tenant-Id", tenantId)
      setMockAkkaBehavior(transformedTenant, contactId, SC_OK, createValkyrieResponse(accountPermissions("some_permission", "a_different_permission")))
      val captor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)
      Mockito.verify(filterChain).doFilter(captor.capture(), Matchers.any(classOf[ServletResponse]))

      assert(mockServletResponse.getStatus == SC_OK)
      assert(captor.getValue.getHeader(HttpDelegationHeaderNames.Delegated).contains("401"))
    }

    it("should 403 when tenant is non-hybrid") {
      setup()
      mockServletRequest.clearHeaders()
      mockServletRequest.setHeader("X-Tenant-Id", "987654")
      mockServletRequest.setHeader("X-Contact-Id", contactId)
      setMockAkkaBehavior(transformedTenant, contactId, SC_OK, createValkyrieResponse(accountPermissions("some_permission", "a_different_permission")))
      val captor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)
      Mockito.verify(filterChain).doFilter(captor.capture(), Matchers.any(classOf[ServletResponse]))

      assert(mockServletResponse.getStatus == SC_OK)
      assert(captor.getValue.getHeader(HttpDelegationHeaderNames.Delegated).contains("403"))
    }

    it("should 502 when valkyrie 404s") {
      setup()
      setMockAkkaBehavior(transformedTenant, contactId, SC_NOT_FOUND, "Not found")
      val captor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)
      Mockito.verify(filterChain).doFilter(captor.capture(), Matchers.any(classOf[ServletResponse]))

      assert(mockServletResponse.getStatus == SC_OK)
      assert(captor.getValue.getHeader(HttpDelegationHeaderNames.Delegated).contains("502"))
    }

    it("should 502 when valkyrie 500s") {
      setup()
      setMockAkkaBehavior(transformedTenant, contactId, SC_INTERNAL_SERVER_ERROR, "Internal Server Error")
      val captor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)
      Mockito.verify(filterChain).doFilter(captor.capture(), Matchers.any(classOf[ServletResponse]))

      assert(mockServletResponse.getStatus == SC_OK)
      assert(captor.getValue.getHeader(HttpDelegationHeaderNames.Delegated).contains("502"))
    }

    it("should 502 when valkyrie gives an unexpected response") {
      setup()
      setMockAkkaBehavior(transformedTenant, contactId, SC_OK, """{"banana":"phone"}""")
      val captor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)
      Mockito.verify(filterChain).doFilter(captor.capture(), Matchers.any(classOf[ServletResponse]))

      assert(mockServletResponse.getStatus == SC_OK)
      assert(captor.getValue.getHeader(HttpDelegationHeaderNames.Delegated).contains("502"))
    }

    it("should 502 when we have an exception while talking to valkyrie") {
      setup()
      Mockito.when(akkaServiceClient.get(Matchers.any(classOf[String]),
        Matchers.eq(s"http://foo.com:8080/account/$transformedTenant/permissions/contacts/any/by_contact/$contactId/effective"),
        Matchers.any(classOf[java.util.Map[String, String]]))).thenThrow(new AkkaServiceClientException("test exception", null))
      val captor = ArgumentCaptor.forClass(classOf[HttpServletRequestWrapper])

      filter.doFilter(mockServletRequest, mockServletResponse, filterChain)
      Mockito.verify(filterChain).doFilter(captor.capture(), Matchers.any(classOf[ServletResponse]))

      assert(mockServletResponse.getStatus == SC_OK)
      assert(captor.getValue.getHeader(HttpDelegationHeaderNames.Delegated).contains("502"))
    }
  }

  describe("do filter should cull appropriately") {
    import play.api.libs.json._

    Set(
      None,
      Some(StandardCharsets.US_ASCII),
      Some(StandardCharsets.ISO_8859_1),
      Some(StandardCharsets.UTF_8),
      Some(StandardCharsets.UTF_16)
    ) foreach { charset =>
      val charsetLabel = charset.map(_.name).getOrElse("NONE")
      val writeJsonResponse: (HttpServletResponse, String) => Unit = charset match {
        case Some(cs) =>
          (response: HttpServletResponse, content: String) => {
            response.setHeader(CommonHttpHeader.CONTENT_TYPE.toString, s"application/json; charset=$charsetLabel")
            response.getOutputStream.write(content.getBytes(cs))
          }
        case None =>
          (response: HttpServletResponse, content: String) => {
            response.setHeader(CommonHttpHeader.CONTENT_TYPE.toString, "application/json")
            response.getOutputStream.print(content)
          }
      }

      def setMockResponseCharset(response: MockHttpServletResponse): Unit =
        charset.map(_.name).foreach(response.setCharacterEncoding)

      it(s"should remove some of the values [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(createGenericValkyrieConfiguration(null))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], createOriginServiceResponse("98765", "123456"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        val content: String = originalResponse.getOutputStreamContent
        val json: JsValue = Json.parse(content)
        assert((json \ "values").as[JsArray].value.size == 1)
        assert((json \ "metadata" \ "count").as[JsNumber].as[Int] == 1)
      }

      it(s"should remove all values [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(createGenericValkyrieConfiguration(null))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], createOriginServiceResponse("234567", "123456"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        val content: String = originalResponse.getOutputStreamContent
        val json: JsValue = Json.parse(content)
        assert((json \ "values").as[JsArray].value.isEmpty)
        assert((json \ "metadata" \ "count").as[JsNumber].as[Int] == 0)
      }

      it(s"should remove no values [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(createGenericValkyrieConfiguration(null))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], createOriginServiceResponse("98765", "98765"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        val content: String = originalResponse.getOutputStreamContent
        val json: JsValue = Json.parse(content)
        assert((json \ "values").as[JsArray].value.size == 2)
        assert((json \ "metadata" \ "count").as[JsNumber].as[Int] == 2)
      }

      it(s"should remove null values [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(setNullDeviceIdAction(createGenericValkyrieConfiguration(null), DeviceIdMismatchAction.REMOVE))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], replaceUriValueWith(createOriginServiceResponse("98765", "98765"), "null"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        val content: String = originalResponse.getOutputStreamContent
        val json: JsValue = Json.parse(content)
        assert((json \ "values").as[JsArray].value.size == 0)
        assert((json \ "metadata" \ "count").as[JsNumber].as[Int] == 0)
      }

      it(s"should not remove null values [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(setNullDeviceIdAction(createGenericValkyrieConfiguration(null), DeviceIdMismatchAction.KEEP))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], replaceUriValueWith(createOriginServiceResponse("98765", "98765"), "null"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        val content: String = originalResponse.getOutputStreamContent
        val json: JsValue = Json.parse(content)
        assert((json \ "values").as[JsArray].value.size == 2)
        assert((json \ "metadata" \ "count").as[JsNumber].as[Int] == 2)
      }

      it(s"should fail on null values [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(setNullDeviceIdAction(createGenericValkyrieConfiguration(null), DeviceIdMismatchAction.FAIL))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], replaceUriValueWith(createOriginServiceResponse("98765", "98765"), "null"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        assert(originalResponse.getStatusCode == SC_INTERNAL_SERVER_ERROR)
      }

      it(s"should remove mismatched values [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(setNullDeviceIdAction(createGenericValkyrieConfiguration(null), DeviceIdMismatchAction.REMOVE))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], replaceUriValueWith(createOriginServiceResponse("98765", "98765"), "\"foo.com/1234\""))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        val content: String = originalResponse.getOutputStreamContent
        val json: JsValue = Json.parse(content)
        assert((json \ "values").as[JsArray].value.size == 0)
        assert((json \ "metadata" \ "count").as[JsNumber].as[Int] == 0)
      }

      it(s"should not remove mismatched values [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(setNullDeviceIdAction(createGenericValkyrieConfiguration(null), DeviceIdMismatchAction.KEEP))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], replaceUriValueWith(createOriginServiceResponse("98765", "98765"), "\"foo.com/1234\""))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        val content: String = originalResponse.getOutputStreamContent
        val json: JsValue = Json.parse(content)
        assert((json \ "values").as[JsArray].value.size == 2)
        assert((json \ "metadata" \ "count").as[JsNumber].as[Int] == 2)
      }

      it(s"should fail on mismatched values [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(setNullDeviceIdAction(createGenericValkyrieConfiguration(null), DeviceIdMismatchAction.FAIL))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], replaceUriValueWith(createOriginServiceResponse("98765", "98765"), "\"foo.com/1234\""))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        assert(originalResponse.getStatusCode == SC_INTERNAL_SERVER_ERROR)
      }

      it(s"should remove no values for account admins with Bypass Account Admin enabled [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(accountPermissions("account_admin", "butts_permission")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(createGenericValkyrieConfiguration(null))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], createOriginServiceResponse("98765", "98765"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        val content: String = originalResponse.getOutputStreamContent
        val json: JsValue = Json.parse(content)
        assert((json \ "values").as[JsArray].value.size == 2)
        assert((json \ "metadata" \ "count").as[JsNumber].as[Int] == 2)
      }

      it(s"should remove values for account admins with Bypass Account Admin disabled [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(accountPermissions("account_admin", "butts_permission")))
        setAdminAkkaBehavior("someTenant", "123456", SC_OK, accountInventory("98765", "98766"))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(createGenericValkyrieConfiguration(null, enableBypassAccountAdmin = false))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], createOriginServiceResponse("98766", "98767"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        val content: String = originalResponse.getOutputStreamContent
        val json: JsValue = Json.parse(content)
        assert((json \ "values").as[JsArray].value.size == 1)
        assert((json \ "metadata" \ "count").as[JsNumber].as[Int] == 1)
      }

      it(s"should remove no values for non-matching resources [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(createGenericValkyrieConfiguration(null))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/foo")
        mockServletRequest.setRequestURI("/foo")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], createOriginServiceResponse("123456", "345678"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        val content: String = originalResponse.getOutputStreamContent
        val json: JsValue = Json.parse(content)
        assert((json \ "values").as[JsArray].value.size == 2)
        assert((json \ "metadata" \ "count").as[JsNumber].as[Int] == 2)
      }

      it(s"should throw a 500 when the regex is un-parseable [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        val configuration: ValkyrieAuthorizationConfig = createGenericValkyrieConfiguration(null)
        configuration.getCollectionResources.getResource.get(0).getCollection.get(0).getJson.getPathToDeviceId.getRegex.setValue("*/*")
        filter.configurationUpdated(configuration)

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], createOriginServiceResponse("123456", "345678"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        assert(originalResponse.getStatusCode == SC_INTERNAL_SERVER_ERROR)

      }

      it(s"should throw a 500 when the capture group is to large [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        val configuration: ValkyrieAuthorizationConfig = createGenericValkyrieConfiguration(null)
        configuration.getCollectionResources.getResource.get(0).getCollection.get(0).getJson.getPathToDeviceId.getRegex.setCaptureGroup(52)
        filter.configurationUpdated(configuration)

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], createOriginServiceResponse("123456", "345678"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        assert(originalResponse.getStatusCode == SC_INTERNAL_SERVER_ERROR)
      }

      it(s"should throw a 500 when the path for the collection is bad [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        val configuration: ValkyrieAuthorizationConfig = createGenericValkyrieConfiguration(null)
        configuration.getCollectionResources.getResource.get(0).getCollection.get(0).getJson.setPathToCollection("$.butts")
        filter.configurationUpdated(configuration)

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], createOriginServiceResponse("123456", "345678"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        assert(originalResponse.getStatusCode == SC_INTERNAL_SERVER_ERROR)
      }

      it(s"should throw a 500 when the path for the device id is bad [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        val configuration: ValkyrieAuthorizationConfig = createGenericValkyrieConfiguration(null)
        configuration.getCollectionResources.getResource.get(0).getCollection.get(0).getJson.getPathToDeviceId.setPath("$.butts")
        filter.configurationUpdated(configuration)

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], createOriginServiceResponse("123456", "345678"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        assert(originalResponse.getStatusCode == SC_INTERNAL_SERVER_ERROR)
      }

      it(s"should throw a 500 when the path for the count is bad [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        val configuration: ValkyrieAuthorizationConfig = createGenericValkyrieConfiguration(null)
        configuration.getCollectionResources.getResource.get(0).getCollection.get(0).getJson.setPathToItemCount("$.butts")
        filter.configurationUpdated(configuration)

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], createOriginServiceResponse("123456", "345678"))
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        assert(originalResponse.getStatusCode == SC_INTERNAL_SERVER_ERROR)
      }

      it(s"should throw a 500 when the response contains bad json [charset: $charsetLabel]") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        val configuration: ValkyrieAuthorizationConfig = createGenericValkyrieConfiguration(null)
        filter.configurationUpdated(configuration)

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        setMockResponseCharset(originalResponse)
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse])))
          .thenAnswer(new Answer[Unit] {
            override def answer(invocation: InvocationOnMock): Unit =
              writeJsonResponse(invocation.getArguments()(1).asInstanceOf[HttpServletResponse], "butts")
          })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        assert(originalResponse.getStatusCode == SC_INTERNAL_SERVER_ERROR)
      }
    }

    List.concat(
      List.range(0, SC_OK),
      List.range(SC_MULTIPLE_CHOICES, 600)
    ).foreach { case (status) =>
      it(s"should not touch the response body if the status is $status") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        filter.configurationUpdated(setNullDeviceIdAction(createGenericValkyrieConfiguration(null), DeviceIdMismatchAction.REMOVE))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        val responseBody = s"This is a response body for status code $status"
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit = {
            invocation.getArguments()(1).asInstanceOf[HttpServletResponse].setStatus(status)
            invocation.getArguments()(1).asInstanceOf[HttpServletResponse].getOutputStream.print(responseBody)
          }
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        assert(originalResponse.getStatus.equals(status))
        assert(originalResponse.getOutputStreamContent.equals(responseBody))
      }
    }

    List(
      ("GET", List(DELETE, POST, PUT, PATCH, HEAD, OPTIONS, CONNECT, TRACE)),
      ("DELETE", List(GET, POST, PUT, PATCH, HEAD, OPTIONS, CONNECT, TRACE)),
      ("POST", List(GET, DELETE, PUT, PATCH, HEAD, OPTIONS, CONNECT, TRACE)),
      ("PUT", List(GET, DELETE, POST, PATCH, HEAD, OPTIONS, CONNECT, TRACE)),
      ("PATCH", List(GET, DELETE, POST, PUT, HEAD, OPTIONS, CONNECT, TRACE)),
      ("HEAD", List(GET, DELETE, POST, PUT, PATCH, OPTIONS, CONNECT, TRACE)),
      ("OPTIONS", List(GET, DELETE, POST, PUT, PATCH, HEAD, CONNECT, TRACE)),
      ("CONNECT", List(GET, DELETE, POST, PUT, PATCH, HEAD, OPTIONS, TRACE)),
      ("TRACE", List(GET, DELETE, POST, PUT, PATCH, HEAD, OPTIONS, CONNECT))
    ).foreach { case (method, configured) =>
      it(s"should not touch the response body if the $method is not in the configuration") {
        setMockAkkaBehavior("someTenant", "123456", SC_OK, createValkyrieResponse(devicePermissions("98765", "view_product")))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
        val valkyrieAuthorizationConfig: ValkyrieAuthorizationConfig = createGenericValkyrieConfiguration(enableBypassAccountAdmin = true, httpMethods = configured)
        filter.configurationUpdated(setNullDeviceIdAction(valkyrieAuthorizationConfig, DeviceIdMismatchAction.REMOVE))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod(method)
        mockServletRequest.setRequestURL("http://foo.com/bar")
        mockServletRequest.setRequestURI("/bar")
        mockServletRequest.setHeader("X-Contact-Id", "123456")
        mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")

        val mockFilterChain = mock[FilterChain]
        val originalResponse: MockHttpServletResponse = new MockHttpServletResponse
        val responseBody = s"This is a response body for HTTP method $method"
        Mockito.when(mockFilterChain.doFilter(Matchers.any(classOf[ServletRequest]), Matchers.any(classOf[ServletResponse]))).thenAnswer(new Answer[Unit] {
          override def answer(invocation: InvocationOnMock): Unit =
            invocation.getArguments()(1).asInstanceOf[HttpServletResponse].getOutputStream.print(responseBody)
        })

        filter.doFilter(mockServletRequest, originalResponse, mockFilterChain)

        assert(originalResponse.getStatus.equals(SC_OK))
        assert(originalResponse.getOutputStreamContent.equals(responseBody))
      }
    }
  }

  describe("when there are no credentials for the valkyrie server") {
    it("should try to apply the original requests x-auth-token") {
      Mockito.when(akkaServiceClient.get(
        CACHE_PREFIX + "any" + "someTenant" + "123456",
        "http://foo.com:8080/account/someTenant/permissions/contacts/any/by_contact/123456/effective",
        Map("X-Auth-Token" -> "someToken")))
        .thenReturn(new ServiceClientResponse(SC_OK, new ByteArrayInputStream(createValkyrieResponse(devicePermissions("123456", "view_product")).getBytes)))

      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
      val configuration: ValkyrieAuthorizationConfig = createGenericValkyrieConfiguration(null)
      configuration.getValkyrieServer.setUsername(null)
      configuration.getValkyrieServer.setPassword(null)
      filter.configurationUpdated(configuration)

      val mockServletRequest = new MockHttpServletRequest
      mockServletRequest.setMethod("GET")
      mockServletRequest.setRequestURL("http://foo.com:8080")
      mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")
      mockServletRequest.setHeader("X-Device-Id", "123456")
      mockServletRequest.setHeader("X-Contact-Id", "123456")
      mockServletRequest.setHeader("X-Auth-Token", "someToken")

      val mockFilterChain = mock[FilterChain]
      filter.doFilter(mockServletRequest, new MockHttpServletResponse, mockFilterChain)

      val responseCaptor = ArgumentCaptor.forClass(classOf[HttpServletResponseWrapper])
      Mockito.verify(mockFilterChain).doFilter(Matchers.any(classOf[ServletRequest]), responseCaptor.capture())
      assert(responseCaptor.getValue.getStatus == SC_OK)
    }
  }

  describe("translates errors states from valkyrie correctly") {
    case class ValkyrieExpectations(valkyrieStatusCode: Int,
                                    filterStatusCode: Int,
                                    valkyrieHeaders: Map[String, String] = Map.empty,
                                    filterHeaders: Map[String, String] = Map.empty)

    val retryTime = new Date().getTime

    implicit def mapToArray(headerMap: Map[String, String]): Array[Header] = {
      headerMap.entrySet.map(entry => new BasicHeader(entry.getKey, entry.getValue)).toArray
    }

    List(ValkyrieExpectations(SC_BAD_REQUEST, SC_INTERNAL_SERVER_ERROR),
         ValkyrieExpectations(SC_UNAUTHORIZED, SC_INTERNAL_SERVER_ERROR),
         ValkyrieExpectations(SC_FORBIDDEN, SC_INTERNAL_SERVER_ERROR),
         ValkyrieExpectations(SC_INTERNAL_SERVER_ERROR, SC_BAD_GATEWAY),
         ValkyrieExpectations(SC_REQUEST_ENTITY_TOO_LARGE, SC_SERVICE_UNAVAILABLE, Map(HttpHeaders.RETRY_AFTER -> retryTime.toString), Map(HttpHeaders.RETRY_AFTER -> retryTime.toString)),
         ValkyrieExpectations(SC_TOO_MANY_REQUESTS, SC_SERVICE_UNAVAILABLE, Map(HttpHeaders.RETRY_AFTER -> retryTime.toString), Map(HttpHeaders.RETRY_AFTER -> retryTime.toString)),
         ValkyrieExpectations(SC_SERVICE_UNAVAILABLE, SC_SERVICE_UNAVAILABLE, Map(HttpHeaders.RETRY_AFTER -> retryTime.toString), Map(HttpHeaders.RETRY_AFTER -> retryTime.toString)))
      .foreach { valkyrie =>
        it(s"should return ${valkyrie.filterStatusCode} when valkyire gives a ${valkyrie.valkyrieStatusCode} when admin creds are present") {
          Mockito.when(akkaServiceClient.get(
            CACHE_PREFIX + "any" + "someTenant" + "123456",
            "http://foo.com:8080/account/someTenant/permissions/contacts/any/by_contact/123456/effective",
            Map("X-Auth-User" -> "someUser", "X-Auth-Token" -> "somePassword")))
            .thenReturn(new ServiceClientResponse(valkyrie.valkyrieStatusCode,
                        valkyrie.valkyrieHeaders,
                        new ByteArrayInputStream(createValkyrieResponse(devicePermissions("123456", "view_product")).getBytes)))

          val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
          filter.configurationUpdated(createGenericValkyrieConfiguration(null))

          val mockServletRequest = new MockHttpServletRequest
          mockServletRequest.setMethod("GET")
          mockServletRequest.setRequestURL("http://foo.com:8080")
          mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")
          mockServletRequest.setHeader("X-Device-Id", "123456")
          mockServletRequest.setHeader("X-Contact-Id", "123456")

          val mockFilterChain = mock[FilterChain]
          val response = new SpringHttpServletResponse()
          filter.doFilter(mockServletRequest, response, mockFilterChain)

          response.getStatus shouldBe (valkyrie.filterStatusCode)
          valkyrie.filterHeaders.entrySet.foreach(entry => response.getHeader(entry.getKey) shouldBe (entry.getValue))
        }
      }

    List(ValkyrieExpectations(SC_BAD_REQUEST, SC_INTERNAL_SERVER_ERROR),
      ValkyrieExpectations(SC_UNAUTHORIZED, SC_UNAUTHORIZED),
      ValkyrieExpectations(SC_FORBIDDEN, SC_FORBIDDEN),
      ValkyrieExpectations(SC_INTERNAL_SERVER_ERROR, SC_BAD_GATEWAY),
      ValkyrieExpectations(SC_REQUEST_ENTITY_TOO_LARGE, SC_REQUEST_ENTITY_TOO_LARGE, Map(HttpHeaders.RETRY_AFTER -> retryTime.toString), Map(HttpHeaders.RETRY_AFTER -> retryTime.toString)),
      ValkyrieExpectations(SC_TOO_MANY_REQUESTS, SC_TOO_MANY_REQUESTS, Map(HttpHeaders.RETRY_AFTER -> retryTime.toString), Map(HttpHeaders.RETRY_AFTER -> retryTime.toString)),
      ValkyrieExpectations(SC_SERVICE_UNAVAILABLE, SC_SERVICE_UNAVAILABLE, Map(HttpHeaders.RETRY_AFTER -> retryTime.toString), Map(HttpHeaders.RETRY_AFTER -> retryTime.toString)))
      .foreach { valkyrie =>
        it(s"should return ${valkyrie.filterStatusCode} when valkyire gives a ${valkyrie.valkyrieStatusCode} when admin creds aren't present") {
          Mockito.when(akkaServiceClient.get(
            CACHE_PREFIX + "any" + "someTenant" + "123456",
            "http://foo.com:8080/account/someTenant/permissions/contacts/any/by_contact/123456/effective",
            Map("X-Auth-Token" -> "someToken")))
            .thenReturn(new ServiceClientResponse(valkyrie.valkyrieStatusCode,
                        valkyrie.valkyrieHeaders,
                        new ByteArrayInputStream(createValkyrieResponse(devicePermissions("123456", "view_product")).getBytes)))

          val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClientFactory, mockDatastoreService)
          val configuration: ValkyrieAuthorizationConfig = createGenericValkyrieConfiguration(null)
          configuration.getValkyrieServer.setUsername(null)
          configuration.getValkyrieServer.setPassword(null)
          filter.configurationUpdated(configuration)

          val mockServletRequest = new MockHttpServletRequest
          mockServletRequest.setMethod("GET")
          mockServletRequest.setRequestURL("http://foo.com:8080")
          mockServletRequest.setHeader("X-Tenant-Id", "hybrid:someTenant")
          mockServletRequest.setHeader("X-Device-Id", "123456")
          mockServletRequest.setHeader("X-Contact-Id", "123456")
          mockServletRequest.setHeader("X-Auth-Token", "someToken")

          val mockFilterChain = mock[FilterChain]
          val response = new SpringHttpServletResponse()
          filter.doFilter(mockServletRequest, response, mockFilterChain)

          response.getStatus shouldBe (valkyrie.filterStatusCode)
          valkyrie.filterHeaders.entrySet.foreach(entry => response.getHeader(entry.getKey) shouldBe (entry.getValue))
        }
      }
  }

  def createGenericValkyrieConfiguration(delegation: DelegatingType = null,
                                         enableBypassAccountAdmin: Boolean = true,
                                         httpMethods: List[HttpMethod] = List(HttpMethod.ALL),
                                         passNonDedicatedTenant: Boolean = false): ValkyrieAuthorizationConfig = {
    val configuration = new ValkyrieAuthorizationConfig
    val server = new ValkyrieServer
    server.setUri("http://foo.com:8080")
    server.setUsername("someUser")
    server.setPassword("somePassword")
    configuration.setValkyrieServer(server)
    configuration.setDelegating(delegation)
    val resource: Resource = new Resource
    val pathRegex: PathRegex = new PathRegex
    pathRegex.setValue("/bar")
    pathRegex.getHttpMethods.addAll(httpMethods.asJava)
    resource.setPathRegex(pathRegex)
    val pathTriplet: PathTriplet = new PathTriplet
    pathTriplet.setPathToCollection("$.values")
    val devicePath: DevicePath = new DevicePath()
    devicePath.setPath("$.uri")
    val regex: Regex = new Regex
    regex.setValue("http://core.rackspace.com/accounts/\\d*/devices/(\\d*)")
    regex.setCaptureGroup(1)
    devicePath.setRegex(regex)
    pathTriplet.setPathToDeviceId(devicePath)
    pathTriplet.setPathToItemCount("$.metadata.count")
    val collection: Collection = new Collection
    collection.setJson(pathTriplet)
    resource.getCollection.add(collection)
    val collectionResources: CollectionResources = new CollectionResources
    collectionResources.getResource.add(resource)
    configuration.setCollectionResources(collectionResources)
    configuration.setEnableBypassAccountAdmin(enableBypassAccountAdmin)
    configuration.setPassNonDedicatedTenant(passNonDedicatedTenant)
    configuration
  }

  def setNullDeviceIdAction(valkyrieAuthorizationConfig: ValkyrieAuthorizationConfig, deviceIdMismatchAction: DeviceIdMismatchAction): ValkyrieAuthorizationConfig = {
    valkyrieAuthorizationConfig.getCollectionResources.setDeviceIdMismatchAction(deviceIdMismatchAction)
    valkyrieAuthorizationConfig
  }

  def setMockAkkaBehavior(tenant: String, contactHeader: String, valkyrieCode: Int, valkyriePayload: String): Unit = {
    Mockito.when(akkaServiceClient.get(
      CACHE_PREFIX + "any" + tenant + contactHeader,
      s"http://foo.com:8080/account/$tenant/permissions/contacts/any/by_contact/$contactHeader/effective",
      Map("X-Auth-User" -> "someUser", "X-Auth-Token" -> "somePassword")))
      .thenReturn(new ServiceClientResponse(valkyrieCode, new ByteArrayInputStream(valkyriePayload.getBytes)))
  }

  def setAdminAkkaBehavior(tenant: String, contactHeader: String, valkyrieCode: Int, valkyriePayload: String): Unit = {
    Mockito.when(akkaServiceClient.get(
      CACHE_PREFIX + "account_admin" + tenant + contactHeader,
      s"http://foo.com:8080/account/$tenant/inventory",
      Map("X-Auth-User" -> "someUser", "X-Auth-Token" -> "somePassword")))
      .thenReturn(new ServiceClientResponse(valkyrieCode, new ByteArrayInputStream(valkyriePayload.getBytes)))
  }

  def createValkyrieResponse(permissions: String*): String = {
    s"""{
        "contact_permissions" :[
         ${permissions.mkString(",")}
         ]
       }"""
  }

  def devicePermissions(deviceId: String, permissionName: String): String = {
    s"""{
         "account_number":862323,
         "contact_id": 818029,
         "id": 0,
         ${if (deviceId != "") "\"item_id\": " + deviceId + "," else ""}
         "item_type_id" : 1,
         "item_type_name" : "devices",
         "permission_name" : "$permissionName",
         "permission_type_id" : 12
       }, {
         "account_number":862323,
         "contact_id": 818029,
         "id": 0,
         "item_id": ${deviceId}1,
         "item_type_id" : 1,
         "item_type_name" : "devices",
         "permission_name" : "${permissionName}1",
         "permission_type_id" : 12
       }"""
  }

  def accountPermissions(permission1: String, permission2: String): String = {
    s"""{
             "item_type_id": 2,
             "permission_type_id": 5,
             "item_type_name": "accounts",
             "contact_id": 817203,
             "account_number": 862323,
             "permission_name": "$permission1",
             "item_id": 862323,
             "id": 0
         },
         {
             "item_type_id": 2,
             "permission_type_id": 9,
             "item_type_name": "accounts",
             "contact_id": 817203,
             "account_number": 862323,
             "permission_name": "$permission2",
             "item_id": 862323,
             "id": 0
         }"""
  }

  def accountInventory(deviceIdOne: String, deviceIdTwo: String): String = {
    s"""{
        |    "inventory": [
        |        {
        |            "status": "Online",
        |            "datacenter": "Datacenter (ABC1)",
        |            "name": "126327-hyp1.abc.rvi.local",
        |            "ipv6_network": "",
        |            "type": "Server",
        |            "primary_ipv4": "",
        |            "primary_ipv6": "",
        |            "primary_ipv4_gateway": "",
        |            "datacenter_id": 1,
        |            "platform": "Super Server",
        |            "nickname": null,
        |            "os": "Penguin Power",
        |            "account_number": 11,
        |            "primary_ipv4_netmask": "",
        |            ${if (deviceIdOne != "") "\"id\": " + deviceIdOne + "," else ""}
        |            "ipv6_server_allocation_block": "",
        |            "permissions": [
        |                "racker"
        |            ]
        |        },
        |        {
        |            "status": "Online",
        |            "datacenter": "Datacenter (ABC1)",
        |            "name": "783621-hyp1.abc.rvi.local",
        |            "ipv6_network": "",
        |            "type": "Server",
        |            "primary_ipv4": "",
        |            "primary_ipv6": "",
        |            "primary_ipv4_gateway": "",
        |            "datacenter_id": 1,
        |            "platform": "Super Server",
        |            "nickname": null,
        |            "os": "Penguin Power",
        |            "account_number": 11,
        |            "primary_ipv4_netmask": "",
        |            ${if (deviceIdTwo != "") "\"id\": " + deviceIdTwo + "," else ""}
        |            "ipv6_server_allocation_block": "",
        |            "permissions": [
        |                "racker"
        |            ]
        |        }
        |    ]
        |}""".stripMargin.trim
  }

  def createOriginServiceResponse(deviceId1: String, deviceId2: String): String = {
    s"""{
        "values": [
            {
                "id": "en6bShuX7a",
                "label": "brad@morgabra.com",
                "ip_addresses": null,
                "metadata": {
                    "userId": "325742",
                    "email": "brad@morgabra.com"
                },
                "managed": false,
                "uri": "http://core.rackspace.com/accounts/1234/devices/$deviceId1",
                "agent_id": "e333a7d9-6f98-43ea-aed3-52bd06ab929f",
                "active_suppressions": [],
                "scheduled_suppressions": [],
                "created_at": 1405963090100,
                "updated_at": 1409247144717
            },
            {
                "id": "enADqSly1y",
                "label": "test",
                "ip_addresses": null,
                "metadata": null,
                "managed": false,
                "uri": "http://core.rackspace.com/accounts/1234/devices/$deviceId2",
                "agent_id": null,
                "active_suppressions": [],
                "scheduled_suppressions": [],
                "created_at": 1411055897191,
                "updated_at": 1411055897191
            }
        ],
        "metadata": {
            "count": 2,
            "limit": 2,
            "marker": null,
            "next_marker": "enB11JvqNv",
            "next_href": "https://monitoring.api.rackspacecloud.com/v1.0/731078/entities?limit=2&marker=enB11JvqNv"
        }
    }"""
  }

  def replaceUriValueWith(jsonString: String, replacement: String): String = {
    jsonString.replaceAll( s"""\"uri\"\\s*:\\s*.+,""", s"""\"uri\": $replacement,""")
  }
}
