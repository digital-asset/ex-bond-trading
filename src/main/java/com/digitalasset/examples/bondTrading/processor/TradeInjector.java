// Copyright (c) 2019, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.examples.bondTrading.processor;

import com.digitalasset.examples.bondTrading.BondTradingMain;
import com.daml.ledger.api.v1.CommandsOuterClass.Command;
import com.daml.ledger.api.v1.CompletionOuterClass;
import com.daml.ledger.api.v1.EventOuterClass;
import com.daml.ledger.api.v1.ValueOuterClass;
import com.daml.ledger.api.v1.ValueOuterClass.Record;
import com.daml.ledger.api.v1.ValueOuterClass.RecordField;
import com.daml.ledger.api.v1.ValueOuterClass.Value;


import io.grpc.ManagedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TradeInjector extends EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(TradeInjector.class);


    private String [] headers = null;
    private String delay_mS = null;
    private String tradeFilePath;
    private long cmdDelay = 0L;

    public TradeInjector(ManagedChannel channel, String packageId, String ledgerId, String party, String tradeFilePath, String delay_mS, Boolean useWallTime) {
        super("Trade Injection", channel, packageId, ledgerId, party, useWallTime);
        this.tradeFilePath = tradeFilePath;
        this.delay_mS = delay_mS;
    }

    @Override
    public int run() {

        // Verify trade file

        if(!new File(tradeFilePath).exists()) {
            logError("Trade file "+tradeFilePath+" does not exist");
            return 1;
        }

        if(delay_mS != null) {
            try {
                cmdDelay = Integer.parseInt(delay_mS);
            } catch (NumberFormatException e) {
                logError("Delay: bad number format: "+delay_mS);
                return 1;
            }
        }
        super.run();

        return 0;
    }

    private int tradeCount = 0;

    private Command countTrades(Command c) {
        tradeCount++;
        return c;
    };

    private Command delayCommand(Command c) {

        if(delay_mS != null) {
            try {
                Thread.sleep(cmdDelay);
            } catch (InterruptedException e) {
                // Do nothing
            }
        }
        return c;
    }

    private void streamTrades() {
        try(Stream<String> stream = Files.lines(Paths.get(tradeFilePath))) {
            stream.map(this::asRecord)
                .filter(r -> !r.isEmpty() && r.get("buyer").equals(getParty()))
                .map(this::asCommand)
                .map(this::countTrades)
                .map(this::delayCommand)
                .forEach(cmd -> submitCommands("TradeInjection", Collections.singletonList(cmd)));
        } catch (IOException e) {
            logError(tradeFilePath+": IO Error: "+e.getMessage());
            logProgress("%s"+String.format(" trade injection terminated after %d",tradeCount));
            BondTradingMain.terminate(2);
        }
        logProgress("%s"+String.format(" trade injection complete, %d trades",tradeCount));
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

    private Command asCommand(Map<String, String> record) {
        ValueOuterClass.Identifier templateId = identityOf("Dvp", "DvpProposal");
        return buildCreateCommand(
                templateId,
                Record.newBuilder()
                    .setRecordId(templateId)
                .addFields(RecordField.newBuilder()
                    .setLabel("c")
                    .setValue(Value.newBuilder().setRecord(dvpTermsFrom(record)))
                    .build()));
    }

    private Record dvpTermsFrom(Map<String,String> record) {

        long settlementTime = getUseWallTime() ? (System.currentTimeMillis() - (1000 * 60)) * 1000 : 0L; // settlement time is an hour ago if we are doing real time.

        return Record.newBuilder()
            .setRecordId(identityOf("DvpTerms", "DvpTerms"))
            .addFields(ValueOuterClass.RecordField.newBuilder()
                .setLabel("buyer")
                .setValue(ValueOuterClass.Value.newBuilder().setParty(record.get("buyer"))))
            .addFields(ValueOuterClass.RecordField.newBuilder()
                .setLabel("seller")
                .setValue(ValueOuterClass.Value.newBuilder().setParty(record.get("seller"))))
            .addFields(ValueOuterClass.RecordField.newBuilder()
                .setLabel("bondIssuer")
                .setValue(ValueOuterClass.Value.newBuilder().setParty(record.get("bondIssuer"))))
            .addFields(ValueOuterClass.RecordField.newBuilder()
                .setLabel("bondIsin")
                .setValue(ValueOuterClass.Value.newBuilder().setText(record.get("bondIsin"))))
            .addFields(ValueOuterClass.RecordField.newBuilder()
                .setLabel("bondAmount")
                .setValue(ValueOuterClass.Value.newBuilder().setNumeric(record.get("bondAmount"))))
            .addFields(ValueOuterClass.RecordField.newBuilder()
                .setLabel("cashIssuer")
                .setValue(ValueOuterClass.Value.newBuilder().setParty(record.get("cashIssuer"))))
            .addFields(ValueOuterClass.RecordField.newBuilder()
                .setLabel("cashCurrency")
                .setValue(ValueOuterClass.Value.newBuilder().setText(record.get("cashCurrency"))))
            .addFields(ValueOuterClass.RecordField.newBuilder()
                .setLabel("cashAmount")
                .setValue(ValueOuterClass.Value.newBuilder().setNumeric(record.get("cashAmount"))))
            .addFields(ValueOuterClass.RecordField.newBuilder()
                .setLabel("settleTime")
                .setValue(ValueOuterClass.Value.newBuilder().setTimestamp(settlementTime)))
            .addFields(ValueOuterClass.RecordField.newBuilder()
                .setLabel("dvpId")
                .setValue(ValueOuterClass.Value.newBuilder().setText(record.get("dvpId"))))
            .build();
    }

    @Override
    Stream<Command> processCreatedEvent(String workflowId, EventOuterClass.CreatedEvent event) {

        switch(identifierToString(event.getTemplateId())) {

            case "Settlement:SettlementProcessor":

                logProgress("%s starts trade injection");

                // I can start streaming my trades when I see my Helper created
                streamTrades();
                BondTradingMain.terminate(0);
                break;

            default:
                break;
        }
        return Stream.empty();
    }

    @Override
    Stream<Command> processArchivedEvent(String workflowId, EventOuterClass.ArchivedEvent event) {
        return Stream.empty();
    }

    @Override
    void processCompletionError(CompletionOuterClass.Completion completion, CompletionRecord completionRecord) {
        log.error("Command ID {} completed with error, status={}, message={}",completion.getCommandId(),completion.getStatus().getCode(),completion.getStatus().getMessage());
        if(completionRecord != null) {
            completionRecord.getCommands().forEach((c -> {
                String tradeId = c.getCreate().getCreateArguments().getFieldsList().stream()
                    .filter(f -> f.getLabel().equals("dvpId"))
                    .collect(Collectors.toList()).get(0).getValue().getText();

                logError(String.format("trade injection of trade %s failed with status %d: '%s'", tradeId, completion.getStatus().getCode(), completion.getStatus().getMessage()));
                BondTradingMain.terminate(1);
            }));
        } else {
            logError("%s "+String.format("trade injection command %s failed with status %d: '%s'", completion.getCommandId(), completion.getStatus().getCode(), completion.getStatus().getMessage()));
        }
    }
}
