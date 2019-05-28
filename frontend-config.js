// Copyright (c) 2019, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { DamlLfValue } from '@da/ui-core';

export const version = {
  schema: 'navigator-config',
  major: 2,
  minor: 0,
};

export const customViews = (userId, party, role) => {
  return {
    assets: {
      type: "table-view",
      title: "Assets",
      source: {
        type: "contracts",
        filter: [
          {
            field: "argument.amount",
            value: "",
          },
          {
            field: "argument.owner",
            value: party,
          },
        ],
        search: "",
        sort: [
          {
            field: "id",
            direction: "ASCENDING"
          }
        ]
      },
      columns: [
        {
          key: "id",
          title: "Contract ID",
          createCell: ({rowData}) => ({
            type: "text",
            value: rowData.id
          }),
          sortable: true,
          width: 80,
          weight: 0,
          alignment: "left"
        },
        {
          key: "type",
          title: "Type",
          createCell: ({rowData}) => ({
            type: "text",
            value: rowData.template.id.substring(0,4)
          }),
          sortable: true,
          width: 80,
          weight: 0,
          alignment: "left"
        },
        {
          key: "argument.owner",
          title: "Owner",
          createCell: ({rowData}) => ({
            type: "text",
            value: DamlLfValue.toJSON(rowData.argument).owner
          }),
          sortable: true,
          width: 80,
          weight: 0,
          alignment: "left"
        },
        {
          key: "symbol",
          title: "Symbol",
          createCell: ({rowData}) => ({
            type: "text",
            value: DamlLfValue.toJSON(rowData.argument).isin || DamlLfValue.toJSON(rowData.argument).currency
          }),
          sortable: true,
          width: 80,
          weight: 0,
          alignment: "left"
        },
        {
          key: "amount",
          title: "Amount",
          createCell: ({rowData}) => ({
            type: "text",
            value: DamlLfValue.toJSON(rowData.argument).amount
          }),
          sortable: true,
          width: 200,
          weight: 3,
          alignment: "left"
        }
      ]
    },
    trades: {
      type: "table-view",
      title: "Trades",
      includeArchived: true,
      source: {
        type: "contracts",
        filter: [
          {
            field: "argument.c.dvpId",
            value: "",
          }
        ],
        search: "",
        sort: [
          {
            field: "id",
            direction: "ASCENDING"
          }
        ]
      },
      columns: [
        {
          key: "id",
          title: "Contract ID",
          createCell: ({rowData}) => ({
            type: "text",
            value: rowData.id
          }),
          sortable: true,
          width: 80,
          weight: 0,
          alignment: "left"
        },
        {
          key: "dvp.id",
          title: "DvP ID",
          createCell: ({rowData}) => ({
            type: "text",
            value: DamlLfValue.toJSON(rowData.argument).c.dvpId
          }),
          sortable: true,
          width: 40,
          weight: 0,
          alignment: "left"
        },
        {
          key: "status",
          title: "Status",
          createCell: ({rowData}) => {
            return {
              type: "text",
              value: rowData.template.id.startsWith("Dvp:DvpProposal")
                ? "Proposed"
                : rowData.template.id.startsWith("Dvp:DvpAllocated")
                ? "Allocated"
                : rowData.template.id.startsWith("DvpNotification:DvpNotification")
                ? "Settled"
                : rowData.template.id.startsWith("Dvp:Dvp")
                ? "Accepted"
                : "Unknown"
            }
          },
          sortable: true,
          width: 80,
          weight: 0,
          alignment: "left"
        },
        {
          key: "buyer",
          title: "Buyer",
          createCell: ({rowData}) => ({
            type: "text",
            value: DamlLfValue.toJSON(rowData.argument).c.buyer
          }),
          sortable: true,
          width: 40,
          weight: 0,
          alignment: "left"
        },
        {
          key: "seller",
          title: "Seller",
          createCell: ({rowData}) => ({
            type: "text",
            value: DamlLfValue.toJSON(rowData.argument).c.seller
          }),
          sortable: true,
          width: 40,
          weight: 0,
          alignment: "left"
        },
        {
          key: "ccy",
          title: "CCY",
          createCell: ({rowData}) => ({
            type: "text",
            value: DamlLfValue.toJSON(rowData.argument).c.cashCurrency
          }),
          sortable: true,
          width: 15,
          weight: 0,
          alignment: "left"
        },
        {
          key: "cash",
          title: "Payment",
          createCell: ({rowData}) => ({
            type: "text",
            value: DamlLfValue.toJSON(rowData.argument).c.cashAmount
          }),
          sortable: true,
          width: 50,
          weight: 0,
          alignment: "left"
        },
        {
          key: "isin",
          title: "ISIN",
          createCell: ({rowData}) => ({
            type: "text",
            value: DamlLfValue.toJSON(rowData.argument).c.bondIsin
          }),
          sortable: true,
          width: 45,
          weight: 0,
          alignment: "left"
        },
        {
          key: "bond",
          title: "Delivery",
          createCell: ({rowData}) => ({
            type: "text",
            value: DamlLfValue.toJSON(rowData.argument).c.bondAmount
          }),
          sortable: true,
          width: 50,
          weight: 3,
          alignment: "left"
        }
      ]
    }
  }
}
