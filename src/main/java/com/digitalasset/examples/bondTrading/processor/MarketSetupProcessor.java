// Copyright (c) 2019, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.examples.bondTrading.processor;

import io.grpc.ManagedChannel;

import com.digitalasset.examples.bondTrading.BondTradingMain;
import com.digitalasset.ledger.api.v1.CommandsOuterClass.Command;
import com.digitalasset.ledger.api.v1.CompletionOuterClass;
import com.digitalasset.ledger.api.v1.EventOuterClass;
import com.digitalasset.ledger.api.v1.ValueOuterClass.Identifier;
import com.digitalasset.ledger.api.v1.ValueOuterClass.Record;
import com.digitalasset.ledger.api.v1.ValueOuterClass.List;
import com.digitalasset.ledger.api.v1.ValueOuterClass.RecordField;
import com.digitalasset.ledger.api.v1.ValueOuterClass.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MarketSetupProcessor extends EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(MarketSetupProcessor.class);

    private String assetFilePath;

    public MarketSetupProcessor(ManagedChannel channel, String packageId, String ledgerId, String assetFilePath, Boolean useWallTime) {
        super("Market Setup", channel,packageId, ledgerId, BondTradingMain.BANK, useWallTime);
        this.assetFilePath = assetFilePath;
    }

    private java.util.List<Map<String,String>> assets;

    @Override
    public int run() {

        logProgress("%s starts market setup");

        super.run();

        // Read in the asset list

        assets = readAssetList();

        // Create setup job to initiate
        submitCommands(
            "MarketSetup",
            Collections.singletonList(
                buildCreateCommand(
                    identityOf("Setup", "MarketSetupJob"), marketSetupJob()
                )));

        return 0;
    }

    @Override
    public Stream<Command> processCreatedEvent(String workflowId, EventOuterClass.CreatedEvent event) {


        switch(identifierToString(event.getTemplateId())) {

            case "Setup:MarketSetupJob":

                logProgress("setup job created");

                // Process the setup job when it is created
                return Stream.of(buildExerciseCommand(
                    event.getTemplateId(), event.getContractId(),
                    "Process",
                    nullArgument("Process"))
                );

            default:
                break;
        }

        return Stream.empty();
    }

    @Override
    public Stream<Command> processExerciseEvent(String workflowId, EventOuterClass.ExercisedEvent event) {

        switch(identifierToString(event.getTemplateId())) {
            default:
                return Stream.empty();
        }
    }

    @Override
    public Stream<Command> processArchivedEvent(String workflowId, EventOuterClass.ArchivedEvent event) {
        return Stream.empty();
    }

    @Override
    void processCompletionSuccess(CompletionOuterClass.Completion completion, CompletionRecord completionRecord) {

        super.processCompletionSuccess(completion, completionRecord);

        java.util.List<Command> commandList = completionRecord.getCommands();
        assert commandList.size() == 1;

        Command command = commandList.get(0);
        log.debug("command complete, type {}, ", command.getCommandCase());
        if(command.hasExercise() && command.getExercise().getChoice().equals("Process")) {
            logProgress("Market Setup complete");
            BondTradingMain.terminate(0);
        }
    }

    @Override
    void processCompletionError(CompletionOuterClass.Completion completion, EventProcessor.CompletionRecord completionRecord) {
        logError(String.format(" fails with status %d: '%s'",completion.getStatus().getCode(), completion.getStatus().getMessage()));
        BondTradingMain.terminate(1);
    }

    private String [] headers = null;

    private java.util.List<Map<String,String>> readAssetList() {

        headers = null;

        try(Stream<String> stream = Files.lines(Paths.get(assetFilePath))) {
            return stream
                .map(this::asRecord)
                .skip(1)
                .collect(Collectors.toList());
        } catch (NoSuchFileException e) {
            logError(assetFilePath+": no such file");
        }
        catch (IOException e) {
            logError(assetFilePath+": IO Error:"+e.getMessage());
        }
        return new ArrayList<>();
    }

    private Map<String,String> asRecord(String line) {

        String [] fields = line.split(",");
        Map<String,String> record = new HashMap<>();

        // On header (first) line, headers is null
        if(headers == null) {
            headers = fields;
        } else {
            for(int i = 0; i < headers.length; i++) {
                record.put(headers[i],fields[i]);
            }
        }
        return record;
    };


    private Record.Builder marketSetupJob() {

        return Record.newBuilder()
            .setRecordId(identityOf("Setup", "MarketSetupJob"))
            .addFields(RecordField.newBuilder()
                .setLabel("issuer")
                .setValue(Value.newBuilder().setParty(BondTradingMain.BANK)))
            .addFields(RecordField.newBuilder()
                .setLabel("entries")
                .setValue(Value.newBuilder().setList(
                    List.newBuilder()
                        .addElements(marketSetupEntry(BondTradingMain.BOB, bondEntries(BondTradingMain.BOB), cashEntries(BondTradingMain.BOB)))
                        .addElements(marketSetupEntry(BondTradingMain.ALICE, bondEntries(BondTradingMain.ALICE), cashEntries(BondTradingMain.ALICE)))
                )));
    }

    private Value.Builder marketSetupEntry(String party, List.Builder bondsEntries, List.Builder cashEntries) {

        Identifier marketSetupEntryIdentifier = Identifier.newBuilder()
            .setPackageId(getPackageId())
            .setModuleName("Setup")
            .setEntityName("MarketSetupEntry")
            .build();

        return Value.newBuilder()
            .setRecord(
                Record.newBuilder()
                    .setRecordId(marketSetupEntryIdentifier)
                    .addFields(RecordField.newBuilder()
                        .setLabel("party")
                        .setValue(Value.newBuilder().setParty(party)))
                    .addFields(RecordField.newBuilder()
                        .setLabel("bondEntries")
                        .setValue(Value.newBuilder().setList(bondsEntries)))
                    .addFields(RecordField.newBuilder()
                        .setLabel("cashEntries")
                        .setValue(Value.newBuilder().setList(cashEntries)))           );
    }

    private Stream<Map<String,String>> assetsFor(String party, String assetName) {
        return assets.stream()
            .filter(r -> r.get("party").equals(party) && r.get("assetName").equals(assetName));
    }

    private List.Builder bondEntries(String party) {
        List.Builder b = List.newBuilder();
        assetsFor(party,"Bond").forEach(r -> b.addElements(
            bondEntry(r.get("symbol"),r.get("amount"))
        ));
        return b;
    }


    private List.Builder cashEntries(String party) {
        List.Builder b = List.newBuilder();
        assetsFor(party,"Cash").forEach(r -> b.addElements(
            cashEntry(r.get("symbol"),r.get("amount"))
        ));
        return b;
    }

    private Value.Builder bondEntry(String isin, String amount) {

        Identifier bondEntryIdentifier = Identifier.newBuilder()
            .setPackageId(getPackageId())
            .setModuleName("Setup")
            .setEntityName("BondEntry")
            .build();

        return Value.newBuilder().setRecord(
            Record.newBuilder()
                .setRecordId(bondEntryIdentifier)
                .addFields(RecordField.newBuilder()
                    .setLabel("isin")
                    .setValue(Value.newBuilder().setText(isin)))
                .addFields(RecordField.newBuilder()
                    .setLabel("amount")
                    .setValue(Value.newBuilder().setDecimal(amount))));
    }

    private Value.Builder cashEntry(String currency, String amount) {

        Identifier cashEntryIdentifier = Identifier.newBuilder()
            .setPackageId(getPackageId())
            .setModuleName("Setup")
            .setEntityName("CashEntry")
            .build();

        return Value.newBuilder().setRecord(
            Record.newBuilder()
                .setRecordId(cashEntryIdentifier)
                .addFields(RecordField.newBuilder()
                    .setLabel("currency")
                    .setValue(Value.newBuilder().setText(currency)))
                .addFields(RecordField.newBuilder()
                    .setLabel("amount")
                    .setValue(Value.newBuilder().setDecimal(amount))));
    }


}
