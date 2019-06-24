# Copyright (c) 2019, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

SHELL := /usr/bin/env bash
.SHELLFLAGS := -euo pipefail -c

# application version
VERSION ?= 1.0

# logic to force use docker builders
ifneq ($(FORCE_DOCKER),true)
	local_da := $(shell which daml)
	local_mvn := $(shell which mvn)
endif


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

# damlc command - use docker or local
damlc_cmd := daml damlc --

sdk_version ?= $(shell cat daml.yaml | grep sdk-version | tr -d ' ' | cut -d':' -f2)
damlc_docker_cmd := \
	docker run -t --rm \
	-v $(PWD):/usr/src/ \
	-w /usr/src \
	digitalasset/daml-sdk:$(sdk_version) $(damlc_cmd)

damlc := $(if $(local_da), $(damlc_cmd), $(damlc_docker_cmd))

# results
dar_test_result := target/DarTests.xml
dar_build_result := target/BondTradingMain.dar

# source
damlsrc := src/main/daml


# dar test
.PHONY: test-dar
test-dar: $(dar_test_result)

# TODO - move to junit files when new version of SDK comes out
$(dar_test_result): $(shell find $(damlsrc) -type f) daml.yaml
	@echo test triggered because these files changed: $?
	$(damlc) test --junit $@ --files $(damlsrc)/Test.daml


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

# maven command - use docker or local
mvn_cmd := mvn

mvn_version ?= 3.6-jdk-8
mvn_docker_cmd := \
	docker run -t --rm \
	-u $$(id -u):$$(id -g) \
	-e MAVEN_CONFIG=/var/maven/.m2 \
	-v $(HOME)/.m2:/var/maven/.m2 \
	-v $(PWD):/usr/src/ \
	-w /usr/src \
	maven:$(mvn_version) $(mvn_cmd) \
		-Duser.home=/var/maven \
		--global-settings /var/maven/.m2/settings.xml

mvn := $(if $(local_mvn), $(mvn_cmd), $(mvn_docker_cmd))

# results
app_build_result := target/ex-bond-trading-$(VERSION).jar
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
	@echo "[STUB] make target $@ is not implemented"


########################
# start the application
########################

docker_runner := \
	docker run -it --rm \
	-v $(PWD):/usr/src/ \
	-p 7500:7500 \
	-w /usr/src \
	digitalasset/daml-sdk:$(sdk_version)-master

.PHONY: start
start: all
	$(if $(local_da),,$(docker_runner)) ./scripts/start


########
# clean
########

.PHONY: clean
clean:
	-rm -vfr target/*
