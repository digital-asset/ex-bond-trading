# Copyright (c) 2019, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

VERSION=1.0
APP_NAME=ex-bond-trading-$(VERSION)
SRC_HOME=src/main/java/com/digitalasset/examples/bondTrading
JAVA_SRC=$(SRC_HOME)/*.java $(SRC_HOME)/processor/*.java
JAVA_RESOURCES=src/main/resources/*
DAML_SRC=src/main/daml/*.daml
DAML_MAIN=src/main/daml/BondTradingMain.daml

APP_JAR_NAME=$(APP_NAME).jar
APP_JAR=lib/$(APP_JAR_NAME)

build: app

clean:
	rm -vrf target/* lib

app: $(APP_JAR)

start:
	sh scripts/start

$(APP_JAR): $(JAVA_SRC) $(JAVA_RESOURCES)
	mvn package ; \
	mkdir -p lib ; mv target/$(APP_JAR_NAME) $(APP_JAR); \
	rm -r dependency-reduced-pom.xml

