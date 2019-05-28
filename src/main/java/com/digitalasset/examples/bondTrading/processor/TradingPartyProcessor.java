// Copyright (c) 2019, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.examples.bondTrading.processor;

import com.digitalasset.examples.bondTrading.BondTradingMain;
import com.digitalasset.ledger.api.v1.CommandsOuterClass.Command;
import com.digitalasset.ledger.api.v1.EventOuterClass.CreatedEvent;
import com.digitalasset.ledger.api.v1.EventOuterClass.ArchivedEvent;
import com.digitalasset.ledger.api.v1.EventOuterClass.ExercisedEvent;
import com.digitalasset.ledger.api.v1.ValueOuterClass;
import com.digitalasset.ledger.api.v1.ValueOuterClass.Value;
import com.digitalasset.ledger.api.v1.ValueOuterClass.Record;
import com.digitalasset.ledger.api.v1.ValueOuterClass.RecordField;

import com.sun.org.apache.xerces.internal.impl.xpath.regex.Match;
import io.grpc.ManagedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TradingPartyProcessor extends EventProcessor {

    public static class Asset {

        public static Asset zero(String symbol) {
            return new Asset(null, BigDecimal.ZERO, symbol, null, null);
        }

        private final String cid;
        private final BigDecimal amount;
        private final String symbol;
        private final String owner;
        private final String issuer;

        Asset(String cid, BigDecimal amount, String symbol, String owner, String issuer) {
            this.cid = cid;
            this.amount = amount;
            this.symbol = symbol;
            this.owner = owner;
            this.issuer = issuer;
        }

        Asset(BigDecimal amount, String symbol) {
            this.cid = null;
            this.amount = amount;
            this.symbol = symbol;
            this.owner = null;
            this.issuer = null;
        }

        public Asset(Integer amount, String symbol) {
            this(new BigDecimal(amount), symbol);
        }

        public String getCid() {
            return cid;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getSymbol() {
            return symbol;
        }

        public String getOwner() {
            return owner;
        }

        public String getIssuer() {
            return issuer;
        }

        Asset sum(Asset a) {
            assert owner != null && owner.equals(a.getOwner());
            assert symbol != null && symbol.equals(a.getSymbol());
            return new Asset(null,amount.add(a.getAmount()),symbol,owner,issuer);
        }

        @Override
        public String toString() {
            return String.format("%,.0f %s", amount, symbol);
        }

        public String logString() {
            return String.format("[%,.0f %s, owner=%s, issuer=%s cid=%s]", amount, symbol, owner, issuer, cid);
        }

        public static Asset cashFrom(CreatedEvent event) {
            return new Asset(
                event.getContractId(),
                new BigDecimal(getRecordValue(event.getCreateArguments(),"amount").getDecimal()),
                getRecordValue(event.getCreateArguments(),"currency").getText(),
                getRecordValue(event.getCreateArguments(),"owner").getParty(),
                getRecordValue(event.getCreateArguments(),"issuer").getParty()
            );
        }

        public static Asset bondFrom(CreatedEvent event) {
            return  new Asset(
                event.getContractId(),
                new BigDecimal(getRecordValue(event.getCreateArguments(),"amount").getDecimal()),
                getRecordValue(event.getCreateArguments(),"isin").getText(),
                getRecordValue(event.getCreateArguments(),"owner").getParty(),
                getRecordValue(event.getCreateArguments(),"issuer").getParty()            );
        }
    }

    public static class Dvp {

        private final String cid;
        private final String buyer;
        private final String seller;
        private final long settleTime;  // In microseconds
        private final String dvpId;
        private final Asset cashLeg;
        private final Asset bondLeg;

        public Dvp(CreatedEvent event) {

            this.cid = event.getContractId();
            this.buyer = getDvpTermValue(event,"buyer").getParty();
            this.seller = getDvpTermValue(event,"seller").getParty();
            this.settleTime = getDvpTermValue(event, "settleTime").getTimestamp();
            this.dvpId = getDvpTermValue(event,"dvpId").getText();

            this.cashLeg = new Asset(
                null,
                new BigDecimal(getDvpTermValue(event,"cashAmount").getDecimal()),
                getDvpTermValue(event,"cashCurrency").getText(),
                getDvpTermValue(event,"buyer").getParty(),
                getDvpTermValue(event,"cashIssuer").getParty()
            );

            this.bondLeg = new Asset(
                null,
                new BigDecimal(getDvpTermValue(event,"bondAmount").getDecimal()),
                getDvpTermValue(event,"bondIsin").getText(),
                getDvpTermValue(event,"seller").getParty(),
                getDvpTermValue(event,"bondIssuer").getParty()
            );
        }

        // Really for testing
        public Dvp(Asset cashLeg, Asset bondLeg) {
            this.cid = null;
            this.buyer = null;
            this.seller = null;
            this.settleTime = 0L;
            this.dvpId = null;
            this.cashLeg = cashLeg;
            this.bondLeg = bondLeg;
        }

        public String getCid() {
            return cid;
        }

        public long getSettleTime() {
            return settleTime;
        }

        public Asset getCashLeg() {
            return cashLeg;
        }

        java.lang.String getDvpId() {
            return dvpId;
        }

        String getBuyer() {
            return buyer;
        }

        String getSeller() {
            return seller;
        }


        Asset getBondLeg() {
            return bondLeg;
        }

        BigDecimal getLegAmount(String legName) {
            assert legName.equals("cash") || legName.equals("bond");
            return (legName.equals("cash") ? cashLeg : bondLeg).getAmount();
        }

        @Override
        public String toString() {
            return String.format(
                "%s buys %s from %s for %s, id=%s",
                buyer,bondLeg.toString(),seller,cashLeg.toString(),dvpId
            );
        }

        public String logString() {
            return "Dvp("+dvpId+", cash="+cashLeg.logString()+", bond="+bondLeg.logString()+", cid="+cid+")";
        }

        private Value getDvpTermValue(CreatedEvent dvpCreated, String field) {
            return getRecordValue(
                getRecordValue(dvpCreated.getCreateArguments(),"c").getRecord(),
                field
            );
        }
    }

    public static class MatchResult {
        public final List<Asset> assetList;
        public final List<Dvp> dvpList;
        final Asset assetTotal;

        MatchResult(String symbol) {
            this(new ArrayList<>(), new ArrayList<>(),  Asset.zero(symbol));
        }

        MatchResult(List<Asset> assetList, List<Dvp> dvpList, Asset assetTotal) {
            this.assetList = assetList;
            this.dvpList = dvpList;
            this.assetTotal = assetTotal;
        }

        public boolean hasSelections() {
            return dvpList.size() > 0;
        }

        Asset getAssetTotal() {
            return assetTotal;
        }

        List<String> dvpString() {
            return dvpList.stream().map(Dvp::toString).collect(Collectors.toList());
        }

        Value asSettlementArgument(String assetLabel, String dvpLabel) {
            assert hasSelections();

            List<String> assetCids = assetList.stream().map(Asset::getCid).collect(Collectors.toList());
            List<String> dvpCids = dvpList.stream().map(Dvp::getCid).collect(Collectors.toList());

            return Value.newBuilder()
                .setRecord(Record.newBuilder()
                    .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel(assetLabel)
                        .setValue(Value.newBuilder().setList(cidListBuilder(assetCids))))
                    .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel(dvpLabel)
                        .setValue(Value.newBuilder().setList(cidListBuilder(dvpCids))
                        ))
                )
                .build();
        }

        @Override
        public String toString() {
            return "MatchResult(assets="+
                String.join(",",assetList.stream().map(Asset::logString).collect(Collectors.toList()))+
                ", dvps="+
                String.join(",",dvpList.stream().map(Dvp::logString).collect(Collectors.toList()))+
                ")";
        }
    }

    public static class SettlementState {

        public final Map<String,Queue<Asset>> cash = new HashMap<>();        // Cash I own, indexec by currency
        public final Map<String,Queue<Asset>> bonds = new HashMap<>();       // Bonds I own, indexed by ISIN
        public final Map<String,Queue<Dvp>> acceptedDvps = new HashMap<>();  // Dvps I'm a buyer on, indexed by currency - these are accepted proposals
        public final Map<String,Queue<Dvp>> allocatedDvps = new HashMap<>(); // Dvps I'm a seller on, indexed by ISIN - these have cash allocated ready for settlement

        public MatchResult allocateBonds(String isin) {
            return matchAssets(bonds.get(isin), allocatedDvps.get(isin), "bond", isin);
        }

        public MatchResult allocateCash(String currency) {
            return matchAssets(cash.get(currency), acceptedDvps.get(currency), "cash", currency);
        }

        /**
         * Return the sum of all cash items
         *
         * @return
         */
        Asset getCashTotal(String currency) {
            assert cash.containsKey(currency);
            return cash.get(currency).stream().reduce(Asset.zero(currency), Asset::sum);
        }

        Asset getBondTotal(String isin) {
            assert bonds.containsKey(isin);
            return bonds.get(isin).stream().reduce(Asset.zero(isin), Asset::sum);
        }

        /**
         * Return a set of ISINs that should be considered for settlement: that is,
         * there are at least SETTLEMENT_BATCH_SIZE dvps for that ISIN waiting for
         * settlement or allocation
         *
         * @return a Set<String> of ISINs
         */
        public Set<String> activeIsins() {
            Set<String> isins = new HashSet<>();
            allocatedDvps.forEach((isin, dvps) -> {
                if (dvps.size() >= BondTradingMain.SETTLEMENT_BATCH_SIZE) isins.add(isin);
            });
            return isins;
        }

        /**
         * Return a set of ISINs that should be considered for settlement: that is,
         * there are at least SETTLEMENT_BATCH_SIZE dvps for that ISIN waiting for
         * settlement or allocation
         *
         * @return a Set<String> of ISINs
         */
        public Set<String> activeCurrencies() {
            Set<String> currencies = new HashSet<>();
            acceptedDvps.forEach((currency, dvps) -> {
                if (dvps.size() >= BondTradingMain.SETTLEMENT_BATCH_SIZE) currencies.add(currency);
            });
            return currencies;
        }

        /**
         * Run the settlement algorithm by considering the current cash, and and dvp state, and generate
         * appropriate commands if any dvps can be allocated or settled from the current bond and cash state
         *
         * This is done by looking at the set of dvps and related assets, and seeing if there are enough assets to
         * satisfy the demand from the dvp set. The asset amounts are totalled, and enough dvps selected so that when assets
         * are consumed, the residual asset is not enough to satisfy the next dvp currently held.
         *
         * This algorithm can be applied to both cash allocation and bond settlement by using the correct dvp leg selector
         * used for amount comparison
         *
         * @return a MatchResult containing the matched assets and dvps
         *
         */

        private static MatchResult matchAssets(Queue<Asset> assetQueue, Queue<Dvp> dvpQueue, String dvpLegSelector, String symbol) {

            if(assetQueue == null || dvpQueue == null) {
                // No assets or dvps to process - return empty
                return new MatchResult(symbol);
            }

            List<Asset> selectedAssets = new LinkedList<>();
            List<Dvp> selectedDvps = new LinkedList<>();
            BigDecimal assetTotal = BigDecimal.ZERO;
            BigDecimal dvpTotal = BigDecimal.ZERO;

            boolean done = assetQueue.isEmpty() || dvpQueue.isEmpty();

            while(!done) {
                BigDecimal assetSum = BigDecimal.ZERO;
                List<Asset> assetList = new ArrayList<>();

                while(assetSum.add(assetTotal).compareTo(dvpQueue.peek().getLegAmount(dvpLegSelector).add(dvpTotal)) < 0 && !assetQueue.isEmpty()) {
                    // While the sum of extracted assets is not enough to satisfy nextDvp, or we run out of assets/dvps...
                    assetSum = assetSum.add(assetQueue.peek().getAmount());   // Update the cash sum
                    assetList.add(assetQueue.poll());                    // Pull the selected asset and add to the assetList
                }
                if(assetSum.add(assetTotal).compareTo(dvpQueue.peek().getLegAmount(dvpLegSelector).add(dvpTotal)) >= 0) {
                    // We have enough assets to allocate or settle nextDvp - add to the result
                    assetTotal = assetTotal.add(assetSum);
                    dvpTotal = dvpTotal.add(dvpQueue.peek().getLegAmount(dvpLegSelector));
                    selectedAssets.addAll(assetList);
                    selectedDvps.add(dvpQueue.poll());
                    done = assetQueue.isEmpty() || dvpQueue.isEmpty();
                } else {
                    // Push back the assets and dvp's extacted so far and mark done
                    assetQueue.addAll(assetList);
                    done = true;
                }
            }
            return new MatchResult(selectedAssets,selectedDvps, new Asset(assetTotal,symbol));
        }
    }

    private static final Logger log = LoggerFactory.getLogger(TradingPartyProcessor.class);

    private String settlementProcessorContractId;

    private SettlementState state = new SettlementState();

    public TradingPartyProcessor(ManagedChannel channel, String packageId, String ledgerId, String party, Boolean useWallTime) {
        super("Settlement", channel,packageId, ledgerId, party, useWallTime);

        submitCommands(
            "SettlementProcessor - " + party,
            Collections.singletonList(
                buildCreateCommand(
                    identityOf("Settlement", "SettlementProcessor"),
                    Record.newBuilder()
                        .setRecordId(identityOf("Settlement", "SettlementProcessor"))
                        .addFields(RecordField.newBuilder()
                            .setLabel("party")
                            .setValue(Value.newBuilder().setParty(party)))
                )));
    }

    @Override
    public int run() {

        log.debug("Starting Trading Party processing for "+getParty());

        super.run();
        return 0;
    }

    @Override
    Stream<Command> processCreatedEvent(String workflowId, CreatedEvent event) {

        Stream<Command> cmdStream = Stream.empty();

        switch(identifierToString(event.getTemplateId())) {

            case "Bond:BondTransferRequest":
            case "Cash:CashTransferRequest":

                log.debug("{} receives {} transfer request, accepting",
                        getParty(),
                        event.getTemplateId().getEntityName().substring(0, 4)
                );
                String newOwner = getRecordValue(event.getCreateArguments(), "newOwner").getParty();

                // Only respond to transfer requests if we are the newOwner
                if (newOwner.equals(getParty())) {
                    cmdStream = Stream.of(
                            buildExerciseCommand(
                                    event.getTemplateId(), event.getContractId(),
                                    "Accept", nullArgument("Accept")));
                }
                break;

            case "Settlement:SettlementProcessor":

                logProgress("settlement Processor for %s created");
                // Save my Helper contract for use in settlement
                settlementProcessorContractId = event.getContractId();
                break;

            // Save Bonds and Cash as they are received, ignoring locked (allocated) cash
            case "Cash:Cash":

                log.debug("{} receives cash id={} {}", getParty(), event.getContractId(), cashDetails(event));
                Asset thisCash = Asset.cashFrom(event);
                // Only save unlocked cash: isUnlocked c = c.owner == c.locker
                if (thisCash.getOwner().equals(getParty()) && getRecordValue(event.getCreateArguments(), "locker").getParty().equals(getParty())) {
                    logProgress("%s " + String.format("receives cash %s", thisCash));
                    state.cash.computeIfAbsent(thisCash.symbol, k -> new ConcurrentLinkedQueue<Asset>()).add(Asset.cashFrom(event));
                }
                break;

            case "Bond:Bond":

                log.debug("{} receives bond id= {} {}", getParty(), event.getContractId(), bondDetails(event));
                Asset thisBond = Asset.bondFrom(event);
                if (thisBond.getOwner().equals(getParty())) {
                    logProgress("%s " + String.format("receives bonds of %s", thisBond));
                    state.bonds.computeIfAbsent(thisBond.getSymbol(), k -> new ConcurrentLinkedQueue<Asset>()).add(thisBond);
                }
                break;

            case "Dvp:DvpProposal":

                log.debug("{} receives proposal {}", getParty(), dvpDetails(event));

                // If I am the seller, accept proposals to sell
                cmdStream = Stream.empty();
                Dvp dvp = new Dvp(event);

                if (dvp.getSeller().equals(getParty())) {
                    logProgress("%s accepts proposal to trade: " + dvp.toString());
                    cmdStream = Stream.of(
                            buildExerciseCommand(
                                    event.getTemplateId(), event.getContractId(),
                                    "Accept", nullArgument("Accept")));
                }
                break;

            case "Dvp:Dvp":

                log.debug("{} receives accepted proposal {}", getParty(), dvpDetails(event));

                // If I am the buyer collect and allocate Dvp's as they come in
                dvp = new Dvp(event);

                if (dvp.getBuyer().equals(getParty())) {
                    state.acceptedDvps
                            .computeIfAbsent(dvp.getCashLeg().getSymbol(), k -> new ConcurrentLinkedQueue<Dvp>())
                            .add(new Dvp(event));
                }
                break;

            case "Dvp:DvpAllocated":

                log.debug("{} receives allocated trade {}", getParty(), dvpDetails(event));

                // If I am the seller, collect and settle allocated Dvp's as they come in
                dvp = new Dvp(event);
                if (dvp.getSeller().equals(getParty())) {
                    state.allocatedDvps
                            .computeIfAbsent(dvp.getBondLeg().getSymbol(), k -> new ConcurrentLinkedQueue<Dvp>())
                            .add(new Dvp(event));
                }
                break;

            case "Dvp:DvpNotification":

                log.debug("{} receives settled trade {}", getParty(), dvpDetails(event));
                dvp = new Dvp(event);
                logProgress("%s " + String.format("settles trade %s", dvp.getDvpId()));
                break;

            default:
                break;
        }
        return cmdStream;
    }

    @Override
    Stream<Command> processArchivedEvent(String workflowId, ArchivedEvent event) {
        log.debug("{} receives an archive event templateId={}, contractId={}",
            getParty(),event.getTemplateId(),event.getContractId()
        );

        switch(identifierToString(event.getTemplateId())) {
            case "Cash:Cash":
                log.debug("{}: cash {} archived", getParty(), event.getContractId());
                state.cash.forEach((s, q) -> q.removeIf(a -> a.cid == event.getContractId()));
                break;

            case "Bond:Bond":
                log.debug("{}: bond {} archived", getParty(), event.getContractId());
                state.bonds.forEach((s, q) -> q.removeIf(a -> a.cid == event.getContractId()));
                break;

            case "Dvp:Dvp":
                log.debug("{}: accepted Dvp {} archived", getParty(), event.getContractId());
                state.acceptedDvps.forEach((s, q) -> q.removeIf(a -> a.cid == event.getContractId()));
                break;

            case "Dvp:DvpAllocated":
                log.debug("{}: allocated Dvp {} archived", getParty(), event.getContractId());
                state.allocatedDvps.forEach((s, q) -> q.removeIf(a -> a.cid == event.getContractId()));
                break;
        }

        return Stream.empty();
    }

    @Override
    void submitCommands(String workFlowId, List<Command> commands) {

        // log the cash and bond balances adter every transaction has processed

        logProgress("%s "+
            String.format(
                "now has balances cash=%s, bonds=%s",
                String.join(", ", state.cash.keySet().stream()
                    .map(k -> state.getCashTotal(k).toString())
                    .collect(Collectors.toList())),
                String.join(", ", state.bonds.keySet().stream()
                    .map(k -> state.getBondTotal(k).toString())
                    .collect(Collectors.toList()))
            ));
        // After processing a transaction result, run settlement for any accepted or allocated DvPs
        commands.addAll(runSettlement().collect(Collectors.toList()));

        super.submitCommands(workFlowId, commands);
    }


    private Command settlementCommandFor(MatchResult matchResult, String choice, String assetLabel, String dvpLabel) {

        assert matchResult.hasSelections();

        return buildExerciseCommand(
            identityOf("Settlement", "SettlementProcessor"),
            settlementProcessorContractId,
            choice,
            matchResult.asSettlementArgument(assetLabel, dvpLabel));
    }

    private String settlementLogMessageFor(MatchResult matchResult, String verb) {
        return "%s allocates " +
            String.format(
                "%s for %s [%s]",
                matchResult.getAssetTotal(),
                verb,
                String.join(", ",matchResult.dvpString())
            );
    }

    private Stream<Command> runSettlement() {
        Stream<Command> allocateCommands =  state.activeCurrencies().stream()
            .map(state::allocateCash)
            .filter(MatchResult::hasSelections)
            .map((MatchResult matchResult) -> {
                logProgress(settlementLogMessageFor(matchResult, "allocates"));
                return settlementCommandFor(
                        matchResult,"AllocateCash","cashCids", "dvpCids");
            });
        Stream<Command> settleCommands =  state.activeIsins().stream()
            .map(state::allocateBonds)
            .filter(MatchResult::hasSelections)
            .map((MatchResult matchResult) -> {
                logProgress(settlementLogMessageFor(matchResult, "settles"));
                return settlementCommandFor(
                        matchResult,"SettleMany","bondCids", "dvpAllocatedCids");
            });

        return Stream.concat(allocateCommands, settleCommands);
    }


    private static ValueOuterClass.List.Builder cidListBuilder(List<String> contractList) {
        ValueOuterClass.List.Builder b = ValueOuterClass.List.newBuilder();
        contractList.forEach(cid -> b.addElements(Value.newBuilder().setContractId(cid)));
        return b;
    }

     private static String cashDetails(CreatedEvent cashEvent) {
        assert cashEvent.getTemplateId().getEntityName().equals("Cash.Cash");
        Record cash = cashEvent.getCreateArguments();
        return String.format("%s %s owned by %s, issued by %s, locked=%s",
            getRecordValue(cash,"amount").getDecimal(),
            getRecordValue(cash,"currency").getText(),
            getRecordValue(cash,"owner").getParty(),
            getRecordValue(cash,"issuer").getParty(),
            getRecordValue(cash,"locker").getParty()
        );
    }

    private static String bondDetails(CreatedEvent bondEvent) {
        assert bondEvent.getTemplateId().getEntityName().equals("Bond.Bond");
        Record cash = bondEvent.getCreateArguments();
        return String.format("%s %s, owned by %s, issued by %s",
            getRecordValue(cash,"amount").getDecimal(),
            getRecordValue(cash,"isin").getText(),
            getRecordValue(cash,"owner").getParty(),
            getRecordValue(cash,"issuer").getParty()
        );
    }

    private static String dvpDetails(CreatedEvent dvpEvent) {
        Record dvpTerms = getRecordValue(dvpEvent.getCreateArguments(),"c").getRecord();
        return String.format("%s buys %s %s from %s for %s%s, dvpId=%s, settling at %s",
            getRecordValue(dvpTerms,"buyer").getParty(),
            getRecordValue(dvpTerms, "bondAmount").getDecimal(),
            getRecordValue(dvpTerms,"bondIsin").getText(),
            getRecordValue(dvpTerms,"seller").getParty(),
            getRecordValue(dvpTerms,"cashAmount").getDecimal(),
            getRecordValue(dvpTerms,"cashCurrency").getText(),
            getRecordValue(dvpTerms,"dvpId").getText(),
            new Timestamp(getRecordValue(dvpTerms,"settleTime").getTimestamp()/1000).toInstant()
        );
    }

    private static String dvpTradeId(CreatedEvent dvpEvent) {
        Record dvpTerms = getRecordValue(dvpEvent.getCreateArguments(),"c").getRecord();
        return getRecordValue(
            (getRecordValue(dvpEvent.getCreateArguments(), "c").getRecord()),
            "dvpId"
        ).getText();
    }
}
