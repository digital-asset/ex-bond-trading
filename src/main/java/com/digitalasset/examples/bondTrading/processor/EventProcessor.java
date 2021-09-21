// Copyright (c) 2019, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.examples.bondTrading.processor;

import com.digitalasset.examples.bondTrading.BondTradingMain;
import com.daml.ledger.api.v1.CommandCompletionServiceGrpc;
import com.daml.ledger.api.v1.CommandCompletionServiceOuterClass;
import com.daml.ledger.api.v1.CommandSubmissionServiceGrpc;
import com.daml.ledger.api.v1.CommandSubmissionServiceOuterClass.SubmitRequest;
import com.daml.ledger.api.v1.CompletionOuterClass;
import com.daml.ledger.api.v1.CompletionOuterClass.Completion;
import com.daml.ledger.api.v1.CommandsOuterClass;
import com.daml.ledger.api.v1.CommandsOuterClass.Command;
import com.daml.ledger.api.v1.CommandsOuterClass.CreateCommand;
import com.daml.ledger.api.v1.CommandsOuterClass.ExerciseCommand;
import com.daml.ledger.api.v1.EventOuterClass;
import com.daml.ledger.api.v1.EventOuterClass.Event;
import com.daml.ledger.api.v1.EventOuterClass.CreatedEvent;
import com.daml.ledger.api.v1.LedgerOffsetOuterClass;
import com.daml.ledger.api.v1.TransactionFilterOuterClass;
import com.daml.ledger.api.v1.TransactionOuterClass.Transaction;
import com.daml.ledger.api.v1.TransactionServiceGrpc;
import com.daml.ledger.api.v1.TransactionServiceOuterClass;
import com.daml.ledger.api.v1.ValueOuterClass;
import com.daml.ledger.api.v1.ValueOuterClass.Identifier;
import com.daml.ledger.api.v1.ValueOuterClass.Record;
import com.daml.ledger.api.v1.ValueOuterClass.Value;


import com.google.rpc.Status;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
 * EventProcessor is s class that holds common state and operations for all processor classes i.e classes that watch
 * the transaction stream and react to various state changeson behalf of a party. It's responsibilities are:
 *
 *  - setting up the stream interfaces for the Transaction Stream and Command Submission services
 *  - parsing and dispatching actions based on received tarnsactions
 *  - submitting commands
 *
 *  Subclasses define the required actions by implementing the abstract methods defined below
 */
abstract class EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    private final String packageId;
    private final String ledgerId;

    private final String command;
    private String party;
    private final Boolean useWallTime;

    private final TransactionServiceGrpc.TransactionServiceStub transactionService;
    private final CommandSubmissionServiceGrpc.CommandSubmissionServiceBlockingStub submissionService;
    private final CommandCompletionServiceGrpc.CommandCompletionServiceStub commandCompletionService;

    public static class CompletionRecord {

        private final String workflowId;
        private final String commandId;
        private final List<Command> commands;

        CompletionRecord(String workflowId, String commandId, List<Command> commands) {
            this.workflowId = workflowId;
            this.commandId = commandId;
            this.commands = commands;
        }

        public String getWorkflowId() {
            return workflowId;
        }

        public String getCommandId() {
            return commandId;
        }

        public List<Command> getCommands() {
            return commands;
        }

    }

    private final ConcurrentHashMap<String, CompletionRecord> pendingCommands = new ConcurrentHashMap<>();

    EventProcessor(String command, ManagedChannel channel, String packageId, String ledgerId, String party, Boolean useWallTime) {
        this.packageId = packageId;
        this.ledgerId = ledgerId;
        this.command = command;
        this.party = party;
        this.transactionService = TransactionServiceGrpc.newStub(channel);
        this.submissionService = CommandSubmissionServiceGrpc.newBlockingStub(channel);
        this.commandCompletionService = CommandCompletionServiceGrpc.newStub(channel);
        this.useWallTime = useWallTime;
    }

    String getPackageId() {
        return packageId;
    }

    String getParty() {
        return party;
    }

    void setParty(String party) {
        this.party = party;
    }

    public Boolean getUseWallTime() {
        return useWallTime;
    }

    abstract Stream<Command> processCreatedEvent(String workflowId, EventOuterClass.CreatedEvent event);        // process and react to Create events
    abstract Stream<Command> processArchivedEvent(String workflowId, EventOuterClass.ArchivedEvent event);      // process and react to Archive events

    public int run() {

        assert party != null;

        setupTransactionService();
        setupCompletionService();

        return 0;
    }

    private void setupTransactionService() {
        TransactionServiceOuterClass.GetTransactionsRequest transactionsRequest = TransactionServiceOuterClass.GetTransactionsRequest.newBuilder()
            .setLedgerId(ledgerId)
            .setBegin(LedgerOffsetOuterClass.LedgerOffset.newBuilder().setBoundary(LedgerOffsetOuterClass.LedgerOffset.LedgerBoundary.LEDGER_BEGIN))
            // we use the default filter since we don't want to filter out any contracts
            .setFilter(
                TransactionFilterOuterClass.TransactionFilter.newBuilder()
                    .putFiltersByParty(party, TransactionFilterOuterClass.Filters.getDefaultInstance()))
            .setVerbose(true)
            .build();

        // this StreamObserver reacts to transactions and prints a message if an error occurs or the stream gets closed
        StreamObserver<TransactionServiceOuterClass.GetTransactionsResponse> transactionObserver = new StreamObserver<TransactionServiceOuterClass.GetTransactionsResponse>() {
            @Override
            public void onNext(TransactionServiceOuterClass.GetTransactionsResponse value) {
                value.getTransactionsList().forEach(EventProcessor.this::processTransaction);
            }

            @Override
            public void onError(Throwable t) {
                log.warn(party + " encountered an error while processing transactions exception={}",t);
                t.printStackTrace();
            }

            @Override
            public void onCompleted() {
                log.info(party + "'s transactions stream completed");
            }
        };
        log.info(party+" starts reading transactions");
        transactionService.getTransactions(transactionsRequest, transactionObserver);
    }

    private void setupCompletionService() {
        CommandCompletionServiceOuterClass.CompletionStreamRequest completionStreamRequest = CommandCompletionServiceOuterClass.CompletionStreamRequest.newBuilder()
            .setLedgerId(ledgerId)
            .setApplicationId(BondTradingMain.APP_ID)
            .setOffset(LedgerOffsetOuterClass.LedgerOffset.newBuilder().setBoundary(LedgerOffsetOuterClass.LedgerOffset.LedgerBoundary.LEDGER_BEGIN))
            .addParties(party)
            .build();

        StreamObserver<CommandCompletionServiceOuterClass.CompletionStreamResponse> completionsObserver = new StreamObserver<CommandCompletionServiceOuterClass.CompletionStreamResponse>() {
            @Override
            public void onNext(CommandCompletionServiceOuterClass.CompletionStreamResponse value) {
                value.getCompletionsList().forEach(c -> processCompletion(c));
            }

            @Override
            public void onError(Throwable t) {
                log.error(party + " encountered an error while processing completions", t);
            }

            @Override
            public void onCompleted() {
                log.info(party + "'s completion stream completed");
            }
        };

        commandCompletionService.completionStream(completionStreamRequest,completionsObserver);
    }

    private void processTransaction(Transaction tx) {
        java.util.List<Command> commands = tx.getEventsList().stream()
            .flatMap(e -> processEvent(tx, e))
            .collect(Collectors.toList());

        submitCommands(tx.getWorkflowId(),commands);
    }

    private Stream<Command> processEvent(Transaction tx, Event event) {

        log.info("{} received event, transactionId={}, workflowid={}, {}", party, tx.getTransactionId(), tx.getWorkflowId(), eventDescription(event));

        if (event.hasCreated()) {
            return processCreatedEvent(tx.getWorkflowId(), event.getCreated());
        } else if(event.hasArchived()) {
            return processArchivedEvent(tx.getWorkflowId(), event.getArchived());
        }
        return Stream.empty(); //Should not happen
    }

    private void processCompletion(Completion completion) {
        Status status = completion.getStatus();
        CompletionRecord completionRecord = pendingCommands.remove(completion.getCommandId());

        if(status.getCode() > 0) {
            log.error("command {} submitted by {} completes with status {}: '{}'", completion.getCommandId(), party, status.getCode(), status.getMessage());
            processCompletionError(completion,completionRecord);
        } else {
            log.debug("command {} submitted by {} completes sucessfully", completion.getCommandId(), party);
            processCompletionSuccess(completion,completionRecord);
        }
    }

    void processCompletionSuccess(CompletionOuterClass.Completion completion, CompletionRecord completionRecord) {
    }

    void processCompletionError(CompletionOuterClass.Completion completion, CompletionRecord completionRecord) {
    }

    Command buildCreateCommand(Identifier templateId, Record.Builder argumentBuilder) {
        return Command.newBuilder()
            .setCreate(
                CommandsOuterClass.CreateCommand.newBuilder()
                    .setTemplateId(templateId)
                    .setCreateArguments(argumentBuilder)
            )
            .build();
    }

    Command buildExerciseCommand(Identifier templateId, String contractId, String choice, Value arguments) {
        return CommandsOuterClass.Command
            .newBuilder()
            .setExercise(CommandsOuterClass.ExerciseCommand
                .newBuilder()
                .setTemplateId(templateId)
                .setContractId(contractId)
                .setChoice(choice)
                .setChoiceArgument(arguments))
            .build();
    }

    ValueOuterClass.Value nullArgument(String choice) {
        return ValueOuterClass.Value.newBuilder()
            .setRecord(Record.newBuilder())
//            .setVariant(ValueOuterClass.Variant.newBuilder()
//                .setConstructor(choice)
//                .setValue(ValueOuterClass.Value.newBuilder().setUnit(Empty.getDefaultInstance())))
            .build();
    }

    void submitCommands(String workFlowId, java.util.List<Command> commands) {

        if(! commands.isEmpty()) {

            String commandId = UUID.randomUUID().toString();

            commands.forEach(cmd -> log.debug("{} sending command {}, commandId={}", party, cmdDescription(cmd), commandId));
            log.info("{} submits commands, commandId={}, workflowId={}", party, commandId, workFlowId);

            SubmitRequest request = SubmitRequest.newBuilder()
                .setCommands(CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(commandId)
                    .setWorkflowId(workFlowId)
                    .setLedgerId(ledgerId)
                    .setParty(party)
                    .setApplicationId(BondTradingMain.APP_ID)
                    .addAllCommands(commands)
                    .build())
                .build();

            pendingCommands.put(commandId,new CompletionRecord(workFlowId, commandId, commands));
            submissionService.submit(request);
        }
    }

    ValueOuterClass.Identifier identityOf(String module, String name) {
        return ValueOuterClass.Identifier.newBuilder()
            .setPackageId(packageId)
            .setModuleName(module)
            .setEntityName(name)
            .build();
    }

    String txDescription(Transaction tx) {
        return "id="+tx.getTransactionId()+", workflowId="+tx.getWorkflowId()+", offset="+tx.getOffset();
    }

    private String eventDescription(Event event) {
        String desc;
        switch (event.getEventCase()) {
            case CREATED:
                CreatedEvent ce = event.getCreated();
                desc = ", templateId="+identifierToString(ce.getTemplateId())+", contractId="+ce.getContractId();
                break;

            case ARCHIVED:
                EventOuterClass.ArchivedEvent ee = event.getArchived();
                desc = ", templateId="+identifierToString(ee.getTemplateId())+", contractId="+ee.getContractId();
                break;

            default:
                desc = "";
        }

        return "type="+event.getEventCase()+desc;
    }

    private String cmdDescription(Command cmd) {
        String desc;
        switch (cmd.getCommandCase()) {
            case CREATE:
                CreateCommand createCommand = cmd.getCreate();
                String cmdArgs = String.join(",",
                    createCommand.getCreateArguments().getFieldsList().stream()
                        .map(field -> field.getLabel()+": "+field.getValue())
                        .collect(Collectors.toList()));
                desc = ", templateId="+identifierToString(createCommand.getTemplateId())+" args="+cmdArgs;
                break;

            case EXERCISE:
                ExerciseCommand exercise = cmd.getExercise();
                String exArgs = String.join(",",
                    exercise.getChoiceArgument().getRecord().getFieldsList().stream()
                        .map(field -> field.getLabel()+": "+field.getValue())
                        .collect(Collectors.toList()));
                desc = ", templateId="+identifierToString(exercise.getTemplateId())+", contractId="+exercise.getContractId()+", choice="+exercise.getChoice()+", args="+exArgs;
                break;

            default:
                desc = "";
        }

        return "type="+cmd.getCommandCase()+desc;
    }

    static Value getRecordValue(Record record, String fieldName) {
        return record.getFieldsList().stream()
            .filter(f -> f.getLabel().equals(fieldName))
            .map(ValueOuterClass.RecordField::getValue)
            .collect(Collectors.toList())
            .get(0);
    }

    void logProgress(String message) {
        BondTradingMain.logProgress(command, String.format(message, party));
    }

    void logError(String message) {
        BondTradingMain.logError(command, message);
    }

    String identifierToString (ValueOuterClass.Identifier identifier) {
        return identifier.getModuleName().concat(":").concat(identifier.getEntityName());
    }
}
