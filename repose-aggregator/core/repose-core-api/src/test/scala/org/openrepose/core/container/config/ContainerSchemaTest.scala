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

package org.openrepose.core.container.config

import org.junit.runner.RunWith
import org.openrepose.commons.test.ConfigValidator
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FunSpec, Matchers}
import org.xml.sax.SAXParseException

@RunWith(classOf[JUnitRunner])
class ContainerSchemaTest extends FunSpec with Matchers {
  val validator = ConfigValidator("/META-INF/schema/container/container-configuration.xsd")

  describe("schema validation") {
    it("should successfully validate the sample config") {
      validator.validateConfigFile("/META-INF/schema/examples/container.cfg.xml")
    }

    it("should successfully validate a list of unique ciphers") {
      val config = """<repose-container xmlns='http://docs.openrepose.org/repose/container/v2.0'>
                     |    <deployment-config>
                     |        <deployment-directory auto-clean="false">/var/repose</deployment-directory>
                     |        <artifact-directory check-interval="60000">/usr/share/repose/filters</artifact-directory>
                     |        <logging-configuration href="file:///etc/repose/log4j2.xml"/>
                     |        <ssl-configuration>
                     |            <keystore-filename>keystore.repose</keystore-filename>
                     |            <keystore-password>manage</keystore-password>
                     |            <key-password>password</key-password>
                     |            <included-ciphers>
                     |                <cipher>.*TLS.*</cipher>
                     |            </included-ciphers>
                     |            <excluded-ciphers>
                     |                <cipher>.*SSL.*</cipher>
                     |            </excluded-ciphers>
                     |        </ssl-configuration>
                     |    </deployment-config>
                     |</repose-container>""".stripMargin
      validator.validateConfigString(config)
    }

    it("should reject config with the same cipher configured multiple times in the included-ciphers list") {
      val config = """<repose-container xmlns='http://docs.openrepose.org/repose/container/v2.0'>
                     |    <deployment-config>
                     |        <deployment-directory auto-clean="false">/var/repose</deployment-directory>
                     |        <artifact-directory check-interval="60000">/usr/share/repose/filters</artifact-directory>
                     |        <logging-configuration href="file:///etc/repose/log4j2.xml"/>
                     |        <ssl-configuration>
                     |            <keystore-filename>keystore.repose</keystore-filename>
                     |            <keystore-password>manage</keystore-password>
                     |            <key-password>password</key-password>
                     |            <included-ciphers>
                     |                <cipher>.*TLS.*</cipher>
                     |                <cipher>.*TLS.*</cipher>
                     |            </included-ciphers>
                     |        </ssl-configuration>
                     |    </deployment-config>
                     |</repose-container>""".stripMargin
      val exception = intercept[SAXParseException] {
        validator.validateConfigString(config)
      }
      exception.getLocalizedMessage should include ("Don't duplicate ciphers")
    }

    it("should reject config with the same cipher configured multiple times in the excluded-ciphers list") {
      val config = """<repose-container xmlns='http://docs.openrepose.org/repose/container/v2.0'>
                     |    <deployment-config>
                     |        <deployment-directory auto-clean="false">/var/repose</deployment-directory>
                     |        <artifact-directory check-interval="60000">/usr/share/repose/filters</artifact-directory>
                     |        <logging-configuration href="file:///etc/repose/log4j2.xml"/>
                     |        <ssl-configuration>
                     |            <keystore-filename>keystore.repose</keystore-filename>
                     |            <keystore-password>manage</keystore-password>
                     |            <key-password>password</key-password>
                     |            <excluded-ciphers>
                     |                <cipher>.*SSL.*</cipher>
                     |                <cipher>.*SSL.*</cipher>
                     |            </excluded-ciphers>
                     |        </ssl-configuration>
                     |    </deployment-config>
                     |</repose-container>""".stripMargin
      val exception = intercept[SAXParseException] {
        validator.validateConfigString(config)
      }
      exception.getLocalizedMessage should include ("Don't duplicate ciphers")
    }

    it("should successfully validate a config with max values for idleTimeout and soLingerTime") {
      val config = """<repose-container xmlns='http://docs.openrepose.org/repose/container/v2.0'>
                     |    <deployment-config idleTimeout="9223372036854775807" soLingerTime="2147483647">
                     |        <deployment-directory auto-clean="false">/var/repose</deployment-directory>
                     |        <artifact-directory check-interval="60000">/usr/share/repose/filters</artifact-directory>
                     |        <logging-configuration href="file:///etc/repose/log4j2.xml"/>
                     |    </deployment-config>
                     |</repose-container>""".stripMargin
      validator.validateConfigString(config)
    }

    it("should reject a config with idleTimeout greater than max value") {
      val config = """<repose-container xmlns='http://docs.openrepose.org/repose/container/v2.0'>
                     |    <deployment-config idleTimeout="9223372036854775808">
                     |        <deployment-directory auto-clean="false">/var/repose</deployment-directory>
                     |        <artifact-directory check-interval="60000">/usr/share/repose/filters</artifact-directory>
                     |        <logging-configuration href="file:///etc/repose/log4j2.xml"/>
                     |    </deployment-config>
                     |</repose-container>""".stripMargin
      val exception = intercept[SAXParseException] {
        validator.validateConfigString(config)
      }
      exception.getLocalizedMessage should include ("is not facet-valid with respect to maxInclusive")
    }

    it("should reject a config with soLingerTime greater than max value") {
      val config = """<repose-container xmlns='http://docs.openrepose.org/repose/container/v2.0'>
                     |    <deployment-config soLingerTime="2147483648">
                     |        <deployment-directory auto-clean="false">/var/repose</deployment-directory>
                     |        <artifact-directory check-interval="60000">/usr/share/repose/filters</artifact-directory>
                     |        <logging-configuration href="file:///etc/repose/log4j2.xml"/>
                     |    </deployment-config>
                     |</repose-container>""".stripMargin
      val exception = intercept[SAXParseException] {
        validator.validateConfigString(config)
      }
      exception.getLocalizedMessage should include ("is not facet-valid with respect to maxInclusive")
    }

    it("should reject a config where cluster IDs are not unique across all patches") {
      val config =
        """<repose-container xmlns='http://docs.openrepose.org/repose/container/v2.0'>
          |    <deployment-config>
          |        <deployment-directory>/var/repose</deployment-directory>
          |        <artifact-directory>/usr/share/repose/filters</artifact-directory>
          |    </deployment-config>
          |
          |    <cluster-config cluster-id="foo" via="test"/>
          |
          |    <cluster-config cluster-id="foo" via="test"/>
          |</repose-container>""".stripMargin
      val exception = intercept[SAXParseException] {
        validator.validateConfigString(config)
      }
      exception.getLocalizedMessage should include("When defining per-cluster configuration, all cluster IDs must be unique")
    }

    it("should accept a config where cluster IDs are unique across all patches") {
      val config =
        """<repose-container xmlns='http://docs.openrepose.org/repose/container/v2.0'>
          |    <deployment-config>
          |        <deployment-directory>/var/repose</deployment-directory>
          |        <artifact-directory>/usr/share/repose/filters</artifact-directory>
          |    </deployment-config>
          |
          |    <cluster-config cluster-id="foo" via="test"/>
          |
          |    <cluster-config cluster-id="bar" via="test"/>
          |</repose-container>""".stripMargin
      validator.validateConfigString(config)
    }

    it("should reject a config where ssl-configuration is partially patched but not in the base") {
      val config =
        """<repose-container xmlns='http://docs.openrepose.org/repose/container/v2.0'>
          |    <deployment-config>
          |        <deployment-directory>/var/repose</deployment-directory>
          |        <artifact-directory>/usr/share/repose/filters</artifact-directory>
          |    </deployment-config>
          |
          |    <cluster-config cluster-id="foo">
          |        <ssl-configuration>
          |            <key-password>password</key-password>
          |        </ssl-configuration>
          |    </cluster-config>
          |</repose-container>""".stripMargin
      val exception = intercept[SAXParseException] {
        validator.validateConfigString(config)
      }
      exception.getLocalizedMessage should include("When defining a new keystore to use, the keystore and key passwords are required")
    }

    it("should successfully validate a config where ssl-configuration is partially patched and is in the base") {
      val config =
        """<repose-container xmlns='http://docs.openrepose.org/repose/container/v2.0'>
          |    <deployment-config>
          |        <deployment-directory>/var/repose</deployment-directory>
          |        <artifact-directory>/usr/share/repose/filters</artifact-directory>
          |        <ssl-configuration>
          |            <keystore-filename>keystore.jks</keystore-filename>
          |            <keystore-password>password</keystore-password>
          |            <key-password>password</key-password>
          |        </ssl-configuration>
          |    </deployment-config>
          |
          |    <cluster-config cluster-id="foo">
          |        <ssl-configuration>
          |            <key-password>other-password</key-password>
          |        </ssl-configuration>
          |    </cluster-config>
          |</repose-container>""".stripMargin
      validator.validateConfigString(config)
    }
  }
}
