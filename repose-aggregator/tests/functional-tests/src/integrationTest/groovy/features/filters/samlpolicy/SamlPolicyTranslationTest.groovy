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

package features.filters.samlpolicy

import framework.ReposeValveTest
import org.openrepose.commons.utils.http.PowerApiHeader
import org.rackspace.deproxy.Deproxy
import spock.lang.Ignore

import javax.servlet.http.HttpServletResponse

class SamlPolicyTranslationTest extends ReposeValveTest {

    def setupSpec() {
        reposeLogSearch.cleanLog()
        deproxy = new Deproxy()
        deproxy.addEndpoint(properties.targetPort)

        def params = properties.defaultTemplateParams
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/filters/samlpolicy", params)
        repose.start()
        reposeLogSearch.awaitByString("Repose ready", 1, 30)
    }

    @Ignore
    def "When using saml-policy filter TEMPLATE it should return OK (200)"() {
        given:
        def headerName = PowerApiHeader.USER.toString()
        def headerValue = "test-user"
        def headers = [(headerName) : headerValue]

        when: "Request contains value(s) of the target header"
        def mc = deproxy.makeRequest(url: reposeEndpoint, headers: headers)

        then: "The request/response should contain the correct value and the status code should be OK (200)"
        mc.handlings[0].request.headers.contains(headerName)
        mc.handlings[0].request.headers.getCountByName(headerName) == 1
        mc.handlings[0].request.headers.getFirstValue(headerName) == headerValue
        mc.receivedResponse.code as Integer == HttpServletResponse.SC_OK
    }
}
