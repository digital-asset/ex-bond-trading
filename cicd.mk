SHELL := /usr/bin/env bash
.SHELLFLAGS := -euo pipefail -c


######
# all
######

.PHONY: all
all: build test

.PHONY: build
build: build-dar build-app

.PHONY: test
test: test-dar test-app test-integration


################
# dar pipeline
################

# test -> build

# damlc command
sdk_version ?= $(shell cat da.yaml | grep sdk-version | tr -d ' ' | cut -d':' -f2)
damlc ?= docker run --rm \
	-v $(PWD):/usr/src/ \
	-w /usr/src \
	digitalasset/daml-sdk:$(sdk_version)-master da run damlc --

# results
dar_test_result := target/DarTests.xml
dar_build_result := target/BondTradingMain.dar

# source
damlsrc := src/main/daml


# dar test
.PHONY: test-dar
test-dar: $(dar_test_result)

# TODO - move to junit files when new version of SDK comes out
$(dar_test_result): $(shell find $(damlsrc) -type f) da.yaml
	@echo test triggered because these files changed: $?
	$(damlc) test --junit $@ $(damlsrc)/Test.daml


# dar build
.PHONY: build-dar
build-dar: $(dar_build_result)

$(dar_build_result): $(dar_test_result)
	@echo build triggered because these files changed: $?
	$(damlc) package $(damlsrc)/$(@F:.dar=.daml) $(basename $@)


################
# app pipeline
################

# build -> test

# maven command
mvn_version ?= 3.6-jdk-8
mvn ?= docker run --rm \
		-u $$(id -u):$$(id -g) \
		-e MAVEN_CONFIG=/var/maven/.m2 \
		-v $(HOME)/.m2:/var/maven/.m2 \
		-v $(PWD):/usr/src/ \
		-w /usr/src \
		maven:$(mvn_version) mvn \
			-Duser.home=/var/maven \
			--global-settings /var/maven/.m2/settings.xml

# results
app_build_result := target/ex-bond-trading-1.0.jar
app_test_result := target/surefire-reports/TEST-com.digitalasset.examples.bondTrading.TradingPartyProcessorTests.xml

# source
appsrc := src/main/java pom.xml


# app build
.PHONY: build-app
build-app: $(app_build_result)

$(app_build_result): $(shell find $(appsrc) -type f)
	@echo build triggered because these files changed: $?
	$(mvn) -DskipTests package


# app test
.PHONY: test-app
test-app: $(app_test_result)

$(app_test_result): $(app_build_result)
	@echo test triggered because these files changed: $?
	$(mvn) test


###################
# integration test
###################

.PHONY: test-integration
test-integration:
	@echo "make target $@ is not implemented"


########
# clean
########

.PHONY: clean
clean:
	-rm -vfr target/*
