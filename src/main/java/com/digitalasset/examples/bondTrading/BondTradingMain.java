// Copyright (c) 2019, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.examples.bondTrading;

import com.digitalasset.daml_lf.DamlLf;
import com.digitalasset.daml_lf.DamlLf1;
import com.digitalasset.examples.bondTrading.processor.MarketSetupProcessor;
import com.digitalasset.examples.bondTrading.processor.TradeInjector;
import com.digitalasset.examples.bondTrading.processor.TradingPartyProcessor;
import com.digitalasset.ledger.api.v1.LedgerIdentityServiceGrpc;
import com.digitalasset.ledger.api.v1.LedgerIdentityServiceGrpc.LedgerIdentityServiceBlockingStub;
import com.digitalasset.ledger.api.v1.LedgerIdentityServiceOuterClass.GetLedgerIdentityRequest;
import com.digitalasset.ledger.api.v1.LedgerIdentityServiceOuterClass.GetLedgerIdentityResponse;
import com.digitalasset.ledger.api.v1.PackageServiceGrpc;
import com.digitalasset.ledger.api.v1.PackageServiceGrpc.PackageServiceBlockingStub;
import com.digitalasset.ledger.api.v1.PackageServiceOuterClass.GetPackageRequest;
import com.digitalasset.ledger.api.v1.PackageServiceOuterClass.GetPackageResponse;
import com.digitalasset.ledger.api.v1.PackageServiceOuterClass.ListPackagesRequest;
import com.digitalasset.ledger.api.v1.PackageServiceOuterClass.ListPackagesResponse;

import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/*
 * The main class of all bond trading bots. The single class accepts a command string as the first argument, and
 * acts accordingly. The class understands the following commands;
 *
 *  - marketSetup: using the facilities in Setup, create the initial market conditions, modulo the trade proposals
 *  - injectTrades: inject a series of trades (DvpProposals),  defined by a CSV file, on behalf of a Party
 *  - runSettlement: run a settlemement bot for a given party
 *
 */
public class BondTradingMain {

    public final static String MAIN_MODULE = "BondTradingMain";

    /**
     * Simple classes to parse arguments
     */

    private static class MarketSetupArgs {

        @Argument(index = 0, required = true, usage = "setup file to load assets from")
        private String assetFilePath;

        public String getAssetFilePath() {
            return assetFilePath;
        }
    }

    private static class TradeInjectorArgs {
        @Option(name = "--delay", aliases = {"-d"}, usage = "Delay for DELAY mS between trade injections", metaVar = "DELAY")
        private String delay_mS = null;

        @Argument(index = 0, required = true, usage = "inject trades for this Party (buyer)")
        private String party = null;

        @Argument(index = 1, required = true, usage = "trade file to load trades from")
        private String tradeFilePath = null;

        public String getDelay_mS() {

            return delay_mS;
        }

        public String getParty() {
            return party;
        }

        public String getTradeFilePath() {
            return tradeFilePath;
        }
    }

    private static class TradingPartyArgs {

        @Argument(index = 0, required = true, usage = "the Party doing the trading")
        private String party;

        public String getParty() {
            return party;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(BondTradingMain.class);

    // application id used for sending commands
    public static final String APP_ID = "BondTradingApp";

    // DAML code package ID
    public static final String PACKAGE_ID = "";

    // constants for referring to the parties
    public static final String BANK = "Bank";
    public static final String ALICE = "Alice";
    public static final String BOB = "Bob";

    public static final String CURRENCY = "USD";

    public static final int SETTLEMENT_BATCH_SIZE = 1;


    private static boolean exitFlag = false;
    private static int exitCode = 0;

    private static Thread mainThread = Thread.currentThread();

    private static final Map<String,String> ANSI_COLORS = createAnsiColors();

    private static Map<String,String> createAnsiColors() {

        Map<String,String> m = new HashMap<>();
        m.put("black","30");
        m.put("red","31");
        m.put("green","32");
        m.put("yellow","33");
        m.put("blue","34");
        m.put("magenta","35");
        m.put("cyan","36");
        m.put("white","37");
        return m;
    }

    public static void terminate(int ec) {
        exitFlag = true;
        exitCode = ec;
        mainThread.interrupt();
    }

    public static boolean parseArguments(Object bean, String[] args) {
        CmdLineParser parser = new CmdLineParser(bean);

        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            logError("",e.getMessage());
            parser.printUsage(System.err);
            return false;
        }

        return true;
    }

    // Pre and post- string to outout around progress lines.
    private static String preLog = "";
    private static String preErr = "";
    private static String postLog = "";

    public static synchronized void logProgress(String command, String message) {
        System.out.print(preLog+command+": "+message+"\n"+postLog);
    }

    public static synchronized void logError(String command, String message) {
        System.err.print(preErr+command+": "+message+"\n"+postLog);
    }

    public static void main(String[] args) {

        mainThread = Thread.currentThread();    // Make sure we have the right main thread

        // Run the main class and wait for termination
        exitCode = new BondTradingMain().run(args);
        if(exitCode == 0) {
            waitForTermination();
        }
        System.exit(exitCode);
    }

    private static void waitForTermination() {

        // Sleep until we are interrupted and the exitFlag is set
        while(!exitFlag) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                if(exitFlag) return;
            }
        }
    }

    /**
     * Args4j cannot parse cmmand lines with options for both the initial and command arguments. So split out the
     * command and it's opions and parse separately.
     *
     * Look for the command verb and collect it and any preceeding arguments.
     *
     * @param args
     * @return
     */
    private static String [] splitCmd(String [] args) {
        int i = 0;
        while(
            i < args.length &&
            !(args[i].equals("marketSetup") || args[i].equals("injectTrades") || args[i].equals("runSettlement"))
            ) i++;

        return i == args.length ? args : Arrays.copyOfRange(args,0, i+1);
    }

    // Class variables

    @Option(name = "--host", aliases = {"-h"}, metaVar = "HOST", usage = "host to connect to")
    private String host = "localhost";

    @Option(name = "--port", aliases = {"-p"}, metaVar = "PORT", usage = "port to connect to")
    private int port = 6865;

    @Option(name = "--color",aliases = {"-c"}, metaVar = "COLOR", usage = "use this text color for output (ANSI colors only)")
    private String textColor = "";

    @Option(name = "--scrollAt",aliases = {"-s"}, metaVar = "SCROLLPORT", usage = "write output in a scrollport at this position")
    private String scrollPortSize = "";

    @Option(name = "--realtime", aliases = { "-r"}, usage = "use real time when sending commands" )
    boolean useWallTime = false;

    @Argument(index = 0, required = true, metaVar = "COMMAND", usage = "command to run: one of 'marketSetup', 'injectTrades', 'runSettlement'")
    private String command = null;

    private int run(String args[]) {

        // Args4j won't parse the full command line wth options for both program and command - so split them apart
        // and parse separately

        String [] cmd = splitCmd(args);

        if(!parseArguments(this, cmd)) return 1;

        if(!setProgressOutput()) return 1;

        // Initialize the command arguments and options
        String [] cmdArgs = Arrays.copyOfRange(args, cmd.length, args.length);

        // Initialize a plaintext gRPC channel
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();

        // fetch the ledger ID, which is used in subsequent requests sent to the ledger
        String ledgerId = fetchLedgerId(channel);
        String packageId = detectPackageId(channel,ledgerId);

        log.debug("Started, ledgerId={}, packageId={}", ledgerId, packageId);

        int retVal;

        switch(command) {
            case "marketSetup":
                // Start up the market setup processor - run by the Bank (issuer)
                MarketSetupArgs setUpArgs = new MarketSetupArgs();
                if(!parseArguments(setUpArgs,cmdArgs)) return 1;
                retVal = new MarketSetupProcessor(channel, packageId, ledgerId, setUpArgs.getAssetFilePath(), useWallTime).run();
                break;

            case "injectTrades":
                // Start trade injectors for each party - they select their proposals from a common file
                TradeInjectorArgs injectorArgs = new TradeInjectorArgs();
                if(!parseArguments(injectorArgs,cmdArgs)) return 1;
                retVal = new TradeInjector(
                    channel, packageId, ledgerId,
                    injectorArgs.getParty(),injectorArgs.getTradeFilePath(), injectorArgs.getDelay_mS(),
                    useWallTime).run();
                break;

            case "runSettlement":
                // Run a settlement bot
                TradingPartyArgs tpArgs = new TradingPartyArgs();
                if(!parseArguments(tpArgs,cmdArgs)) return 1;
                retVal = new TradingPartyProcessor(channel, packageId, ledgerId, tpArgs.getParty(), useWallTime).run();
                break;

            default:
                System.err.print("Command '"+command+"' not recognized");
                retVal = 1;
                break;
        }
        return retVal;
    }

    /**
     * Set pre and post cursor control strings for progress output
     */
    private boolean setProgressOutput() {
        if(!textColor.equals("")) {
            // Add ANSI sequences to control text color on progress output
            String code = ANSI_COLORS.get(textColor);
            if (code == null) {
                System.err.println("Unknown ANSI color: " + textColor);
                return false;
            }
            preLog = "\033[" + code + "m";
            preErr = "\033[" + code + "m";
            postLog = "\033[37m";
        }

        if(!scrollPortSize.equals("")) {
            String statusLine = System.getenv("LINES");
            // Add codes to log output in scroll window - save cursor, move to scrollport, clear line, output then restore cursor
            preLog = String.format("\0337\033[%s;1H\033[2K",scrollPortSize)+preLog;
            preErr = String.format("\0337\033[%s;1H\033[2K",statusLine)+preErr;
            postLog = postLog+"\0338";
        }
        return true;
    }
    /**
     * Fetches the ledger id via the Ledger Identity Service.
     *
     * @param channel the gRPC channel to use for services
     * @return the ledger id as provided by the ledger
     */
    private static String fetchLedgerId(ManagedChannel channel) {
        LedgerIdentityServiceBlockingStub ledgerIdService = LedgerIdentityServiceGrpc.newBlockingStub(channel);
        GetLedgerIdentityResponse identityResponse = ledgerIdService.getLedgerIdentity(GetLedgerIdentityRequest.getDefaultInstance());
        return identityResponse.getLedgerId();
    }

    /**
     * Inspects all DAML packages that are registered on the ledger and returns the id of the package that contains the BondTrading module.
     * This is useful during development when the DAML model changes a lot, so that the package id doesn't need to be updated manually
     * after each change.
     *
     * @param channel  the gRPC channel to use for services
     * @param ledgerId the ledger id to use for requests
     * @return the package id of the example DAML module
     */
    private static String detectPackageId(ManagedChannel channel, String ledgerId) {
        PackageServiceBlockingStub packageService = PackageServiceGrpc.newBlockingStub(channel);

        // fetch a list of all package ids available on the ledger
        ListPackagesResponse packagesResponse = packageService.listPackages(ListPackagesRequest.newBuilder().setLedgerId(ledgerId).build());

        // find the package that contains the Bond Trading module
        for (String packageId : packagesResponse.getPackageIdsList()) {
            GetPackageResponse getPackageResponse = packageService.getPackage(GetPackageRequest.newBuilder().setLedgerId(ledgerId).setPackageId(packageId).build());
            try {
                // parse the archive payload
                DamlLf.ArchivePayload payload = DamlLf.ArchivePayload.parseFrom(getPackageResponse.getArchivePayload());
                // get the DAML LF package
                DamlLf1.Package lfPackage = payload.getDamlLf1();
                // check if the Bond Trading module is in the current package package
                Optional<DamlLf1.Module> bondTradingModule = lfPackage.getModulesList().stream()
                    .filter(m -> m.getName().getSegmentsList().contains(MAIN_MODULE)).findFirst();

                if (bondTradingModule.isPresent())
                    return packageId;

            } catch (InvalidProtocolBufferException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
        // No package on the ledger contained the PingPong module
        throw new RuntimeException("Module '"+MAIN_MODULE+"' is not available on the ledger");
    }
}
