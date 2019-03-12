// Copyright (c) 2019, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: (Apache-2.0 OR BSD-3-Clause)

package com.digitalasset.examples.bondTrading;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import com.digitalasset.examples.bondTrading.processor.TradingPartyProcessor.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@RunWith(JUnitPlatform.class)
@DisplayName("A TradingPartyProcessor")
public class TradingPartyProcessorTests {

    public static String ISIN = "US-99999999-0";

    @Nested
    @DisplayName("When settling")
    class SettlementMatching {

        SettlementState state;

        @Nested
        @DisplayName("and there are no assets or dvps")
        class WnenEmpty {

            @BeforeEach
            void createEmpty() {
                state = new SettlementState();
            }

            @Test
            @DisplayName("has no selections")
            void hasNoSelections() {
                MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                assertFalse(m.hasSelections());

            }

            @Test
            @DisplayName("has empty selections")
            void hasEmptySelections() {
                MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                assertTrue(m.assetList.isEmpty());
                assertTrue(m.dvpList.isEmpty());
            }
        }

        @Nested
        @DisplayName("and there are no assets but there accepted dvps")
        class EmptyAssets {

            Dvp dvp;

            @BeforeEach
            void createEmpty() {
                state = new SettlementState();
                dvp = new Dvp(
                    new Asset(10000, BondTradingMain.CURRENCY),
                    new Asset(10000, ISIN)
                );
                state.acceptedDvps.put(BondTradingMain.CURRENCY, new ConcurrentLinkedQueue<Dvp>(Collections.singletonList(dvp)));
            }

            @Test
            @DisplayName("has no selections")
            void hasNoSelections() {
                MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                assertFalse(m.hasSelections());
            }

            @Test
            @DisplayName("has empty selections")
            void hasEmptySelections() {
                MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                assertTrue(m.assetList.isEmpty());
                assertTrue(m.dvpList.isEmpty());
            }

            @Test
            @DisplayName("leaves dvps in place")
            void leavesDvps() {
                MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                assertTrue(state.acceptedDvps.size() == 1);
                assertTrue(state.acceptedDvps.get(BondTradingMain.CURRENCY).size() == 1);
                assertTrue(state.acceptedDvps.get(BondTradingMain.CURRENCY).poll() == dvp);
            }
        }

        @Nested
        @DisplayName("and there are assets but no dvps")
        class EmptyDvps {

            Asset asset;

            @BeforeEach
            void createEmpty() {
                state = new SettlementState();
                asset = new Asset(10000, BondTradingMain.CURRENCY);
                state.cash.computeIfAbsent(asset.getSymbol(), k -> new ConcurrentLinkedQueue<>()).add(asset);
            }

            @Test
            @DisplayName("has no selections")
            void hasNoSelections() {
                MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                assertFalse(m.hasSelections());
            }

            @Test
            @DisplayName("has empty selections")
            void hasEmptySelections() {
                MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                assertTrue(m.assetList.isEmpty());
                assertTrue(m.dvpList.isEmpty());
            }

            @Test
            @DisplayName("leaves assets in place")
            void leavesDvps() {
                MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                assertTrue(state.cash.size() == 1);
                assertTrue(state.cash.get(BondTradingMain.CURRENCY).poll() == asset);
            }
        }

        @Nested
        @DisplayName("and there are assets and accepted dvps")
        class AssetsAndDvps {

            @Nested
            @DisplayName("that can allocate cash exactly")
            class CanAllocateCashExactly {

                List<Asset> assets = Arrays.asList(
                    new Asset(10000,BondTradingMain.CURRENCY),
                    new Asset(10000,BondTradingMain.CURRENCY));

                Dvp dvp = new Dvp(
                    new Asset(20000, BondTradingMain.CURRENCY),
                    new Asset(100000, ISIN));

                @BeforeEach
                void setup() {
                    state = new SettlementState();
                    state.cash.computeIfAbsent(BondTradingMain.CURRENCY, k -> new ConcurrentLinkedQueue<>()).addAll(assets);
                    state.acceptedDvps.put(
                            BondTradingMain.CURRENCY,
                        new ConcurrentLinkedQueue<Dvp>(
                            Collections.singletonList(dvp)));
                }

                @Test
                @DisplayName("then the match has selections")
                void hasSelections() {
                    MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                    assertTrue(m.hasSelections());
                }

                @Test
                @DisplayName("then all the assets have been selected")
                void correctSelections() {
                    MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                    assertTrue(m.assetList.size() == 2);
                    assertTrue(
                        m.assetList.get(0) == assets.get(0) &&
                            m.assetList.get(1) == assets.get(1)
                    );
                    assertTrue(m.dvpList.size() == 1);
                    assertTrue(m.dvpList.get(0) == dvp);
                    assertTrue(state.cash.get(BondTradingMain.CURRENCY).isEmpty());
                    assertTrue(state.acceptedDvps.get(BondTradingMain.CURRENCY).size() == 0);
                }
            }

            @Nested
            @DisplayName("that can allocate excess cash")
            class CanAllocateCash {

                List<Asset> assets = Arrays.asList(
                    new Asset(10000,BondTradingMain.CURRENCY),
                    new Asset(15000,BondTradingMain.CURRENCY));

                Dvp dvp = new Dvp(
                    new Asset(20000, BondTradingMain.CURRENCY),
                    new Asset(100000, ISIN));

                @BeforeEach
                void setup() {
                    state = new SettlementState();
                    state.cash.computeIfAbsent(BondTradingMain.CURRENCY, k -> new ConcurrentLinkedQueue<>()).addAll(assets);
                    state.acceptedDvps.put(
                            BondTradingMain.CURRENCY,
                        new ConcurrentLinkedQueue<Dvp>(
                            Collections.singletonList(dvp)));
                }

                @Test
                @DisplayName("then the match has selections")
                void hasSelections() {
                    MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                    assertTrue(m.hasSelections());
                }

                @Test
                @DisplayName("then all the assets have been selected")
                void correctSelections() {
                    MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                    assertTrue(m.assetList.size() == 2);
                    assertTrue(
                        m.assetList.get(0) == assets.get(0) &&
                            m.assetList.get(1) == assets.get(1)
                    );
                    assertTrue(m.dvpList.size() == 1);
                    assertTrue(m.dvpList.get(0) == dvp);
                    assertTrue(state.cash.get(BondTradingMain.CURRENCY).isEmpty());
                    assertTrue(state.acceptedDvps.get(BondTradingMain.CURRENCY).size() == 0);
                }
            }

            @Nested
            @DisplayName("that can allocate excess cash with a residual")
            class CanAllocateCashWithResudual {

                List<Asset> assets = Arrays.asList(
                    new Asset(10000,BondTradingMain.CURRENCY),
                    new Asset(15000,BondTradingMain.CURRENCY),
                    new Asset(1000,BondTradingMain.CURRENCY)
                    );

                Dvp dvp = new Dvp(
                    new Asset(20000, BondTradingMain.CURRENCY),
                    new Asset(100000, ISIN));

                @BeforeEach
                void setup() {
                    state = new SettlementState();
                    state.cash.computeIfAbsent(BondTradingMain.CURRENCY, k -> new ConcurrentLinkedQueue<>()).addAll(assets);
                    state.acceptedDvps.put(
                            BondTradingMain.CURRENCY,
                        new ConcurrentLinkedQueue<Dvp>(
                            Collections.singletonList(dvp)));
                }

                @Test
                @DisplayName("then the match has selections")
                void hasSelections() {
                    MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                    assertTrue(m.hasSelections());
                }

                @Test
                @DisplayName("then all the assets have been selected")
                void correctSelections() {
                    MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                    assertTrue(m.assetList.size() == 2);
                    assertTrue(
                        m.assetList.get(0) == assets.get(0) &&
                            m.assetList.get(1) == assets.get(1)
                    );
                    assertTrue(m.dvpList.size() == 1);
                    assertTrue(m.dvpList.get(0) == dvp);
                    assertTrue(state.cash.size() == 1);
                    assertTrue(state.cash.get(BondTradingMain.CURRENCY).peek() == assets.get(2));
                    assertTrue(state.acceptedDvps.get(BondTradingMain.CURRENCY).size() == 0);
                }
            }

            @Nested
            @DisplayName("that cannot allocate cash")
            class CannotAllocateCash {

                List<Asset> assets = Arrays.asList(
                    new Asset(10000,BondTradingMain.CURRENCY));

                Dvp dvp = new Dvp(
                    new Asset(20000, BondTradingMain.CURRENCY),
                    new Asset(100000, ISIN));

                @BeforeEach
                void setup() {
                    state = new SettlementState();
                    state.cash.computeIfAbsent(BondTradingMain.CURRENCY, k -> new ConcurrentLinkedQueue<>()).addAll(assets);
                    state.acceptedDvps.put(
                        BondTradingMain.CURRENCY,
                        new ConcurrentLinkedQueue<Dvp>(
                            Collections.singletonList(dvp)));
                }

                @Test
                @DisplayName("then the match does not has selections")
                void hasSelections() {
                    MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                    assertFalse(m.hasSelections());
                }

                @Test
                @DisplayName("then noe assets have been selected")
                void correctSelections() {
                    MatchResult m = state.allocateCash(BondTradingMain.CURRENCY);
                    assertTrue(m.assetList.size() == 0);
                    assertTrue(m.dvpList.size() == 0);
                    assertTrue(state.cash.size() == 1);
                    assertTrue(state.cash.get(BondTradingMain.CURRENCY).peek() == assets.get(0));
                    assertTrue(state.acceptedDvps.get(BondTradingMain.CURRENCY).size() == 1);
                    assertTrue(state.acceptedDvps.get(BondTradingMain.CURRENCY).peek() == dvp);
                }
            }
        }

    }

}
