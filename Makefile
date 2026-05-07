OPENAPI_JSON := smithy-model/build/smithy/openapi/openapi/ArtifactMgmt.openapi.json
JAVA_HOME_11 := $(shell /usr/libexec/java_home -v 11 2>/dev/null || echo "")

OPENAPI_GENERATOR_VERSION := 7.9.0
OPENAPI_GENERATOR_JAR := build/openapi-generator-cli.jar
OPENAPI_GENERATOR_URL := https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/$(OPENAPI_GENERATOR_VERSION)/openapi-generator-cli-$(OPENAPI_GENERATOR_VERSION).jar

.PHONY: sdk smithy-build python-sdk java-sdk clean-sdk

sdk: python-sdk java-sdk

smithy-build:
	@export PATH="$$HOME/bin:$$PATH" && cd smithy-model && smithy build

$(OPENAPI_JSON): smithy-build

$(OPENAPI_GENERATOR_JAR):
	mkdir -p build
	curl -fsSL $(OPENAPI_GENERATOR_URL) -o $@

python-sdk: $(OPENAPI_JSON) $(OPENAPI_GENERATOR_JAR)
	java -jar $(OPENAPI_GENERATOR_JAR) generate \
		-g python \
		-i $(OPENAPI_JSON) \
		-o build/python-sdk \
		--additional-properties=packageName=artifact_mgmt_client,projectName=artifact-mgmt-client \
		--skip-validate-spec
	pip install --quiet build
	cd build/python-sdk && python -m build

java-sdk: $(OPENAPI_JSON) $(OPENAPI_GENERATOR_JAR)
	java -jar $(OPENAPI_GENERATOR_JAR) generate \
		-g java \
		--library=native \
		-i $(OPENAPI_JSON) \
		-o build/java-sdk-generated \
		--additional-properties=\
apiPackage=com.anthropic.artifactmgmt.client.api,\
modelPackage=com.anthropic.artifactmgmt.client.model,\
invokerPackage=com.anthropic.artifactmgmt.client,\
java21=true,\
useJakartaEe=true,\
openApiNullable=false \
		--skip-validate-spec
	cd java-sdk && JAVA_HOME=$(JAVA_HOME_11) ./gradlew build -x spotlessCheck

clean-sdk:
	rm -rf build/python-sdk build/java-sdk-generated $(OPENAPI_GENERATOR_JAR) java-sdk/build
