# Copyright (c) 2019, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

SHELL := /usr/bin/env bash
.SHELLFLAGS := -euo pipefail -c

# application version
VERSION ?= 1.0

daml := ~/.daml/bin/daml
sdk_version ?= $(shell cat daml.yaml | grep sdk-version | tr -d ' ' | cut -d':' -f2)

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
# sdk install
################

.PHONY: sdk
sdk: daml.yaml
	@echo install triggered because these files changed: $?
	$(daml) install $(sdk_version)

################
# dar pipeline
################

# test -> build

# results
dar_test_result := target/DarTests.xml
dar_build_result := target/BondTradingMain.dar

# source
damlsrc := src/main/daml


# dar test
.PHONY: test-dar
test-dar: sdk $(dar_test_result)

# TODO - move to junit files when new version of SDK comes out
$(dar_test_result): $(shell find $(damlsrc) -type f) daml.yaml
	@echo test triggered because these files changed: $?
	$(daml) test --junit $@


# dar build
.PHONY: build-dar
build-dar: $(dar_build_result)

$(dar_build_result): $(dar_test_result)
	@echo build triggered because these files changed: $?
	$(daml) build -o $@


################
# app pipeline
################

# build -> test

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
	mvn -DskipTests package


# app test
.PHONY: test-app
test-app: $(app_test_result)

$(app_test_result): $(app_build_result)
	@echo test triggered because these files changed: $?
	mvn test


###################
# integration test
###################

.PHONY: test-integration
test-integration:
	@echo "[STUB] make target $@ is not implemented"


########################
# start the application
########################

.PHONY: start
start: all
	./scripts/start

########
# clean
########

.PHONY: clean
clean:
	-rm -vfr target/*
