import { useCallback, useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { api } from "@/lib/api";
import { toSnakeCase, displayValue } from "@/lib/utils";
import type { RegisterMeta, EntityRecord } from "@/lib/types";
import { useUiEvents } from "@/hooks/use-ui-events";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { DatePicker } from "@/components/date-picker";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "@/components/ui/table";
import { PageHeader } from "@/components/page-header";
import { SkeletonTable } from "@/components/skeleton-table";

export function RegisterReportView() {
  const { name } = useParams<{ name: string }>();
  const [meta, setMeta] = useState<RegisterMeta | null>(null);
  const [movements, setMovements] = useState<EntityRecord[]>([]);
  const [balances, setBalances] = useState<EntityRecord[]>([]);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  useEffect(() => {
    if (!name) return;
    setMeta(null);
    setMovements([]);
    setBalances([]);
    api.getRegisters().then((all) => {
      const found = all.find((r) => toSnakeCase(r.name) === name);
      setMeta(found ?? null);
    });
  }, [name]);

  useEffect(() => {
    if (!name || !meta || toSnakeCase(meta.name) !== name) return;
    api.getMovements(name).then(setMovements);
    if (meta.type === "BALANCE") {
      api.getBalance(name).then(setBalances);
    }
  }, [name, meta]);

  const reload = useCallback(() => {
    if (!name || !meta || toSnakeCase(meta.name) !== name) return;
    api.getMovements(name).then(setMovements);
    if (meta.type === "BALANCE") {
      api.getBalance(name).then(setBalances);
    }
  }, [name, meta]);

  useUiEvents(useCallback((event) => {
    if (event.entityType === "register" && (event.entityName === meta?.name || event.entityName === "*")) {
      reload();
    }
  }, [meta?.name, reload]));

  const loadTurnover = () => {
    if (!name || !from || !to) return;
    api.getTurnover(name, from, to).then(setBalances);
  };

  if (!meta) {
    return (
      <div className="animate-in-page">
        <div className="mb-6">
          <Skeleton className="h-8 w-48 mb-2" />
          <Skeleton className="h-4 w-24" />
        </div>
        <SkeletonTable columns={4} rows={5} />
      </div>
    );
  }

  const allColumns = [...meta.dimensions, ...meta.resources];

  return (
    <div className="animate-in-page">
      <PageHeader
        title={meta.name}
        breadcrumbs={[{ label: "Registers" }, { label: meta.name }]}
        badge={<Badge variant="outline">{meta.type}</Badge>}
      />

      <Tabs defaultValue={meta.type === "BALANCE" ? "balance" : "movements"}>
        <TabsList>
          {meta.type === "BALANCE" && (
            <TabsTrigger value="balance">Balance</TabsTrigger>
          )}
          <TabsTrigger value="movements">Movements</TabsTrigger>
          <TabsTrigger value="turnover">Turnover</TabsTrigger>
        </TabsList>

        {meta.type === "BALANCE" && (
          <TabsContent value="balance">
            <Card>
              <CardHeader>
                <CardTitle className="text-lg">Current Balances</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="rounded-lg border overflow-hidden">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        {allColumns.map((c) => (
                          <TableHead key={c.fieldName}>{c.displayName}</TableHead>
                        ))}
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {balances.length === 0 && (
                        <TableRow>
                          <TableCell
                            colSpan={allColumns.length}
                            className="text-center text-muted-foreground py-8"
                          >
                            No data
                          </TableCell>
                        </TableRow>
                      )}
                      {balances.map((row, i) => (
                        <TableRow key={i}>
                          {allColumns.map((c) => (
                            <TableCell key={c.fieldName}>
                              {displayValue(c, row[c.columnName], row)}
                            </TableCell>
                          ))}
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              </CardContent>
            </Card>
          </TabsContent>
        )}

        <TabsContent value="movements">
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">Movement Records</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="rounded-lg border overflow-hidden">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Period</TableHead>
                      <TableHead>Type</TableHead>
                      {allColumns.map((c) => (
                        <TableHead key={c.fieldName}>{c.displayName}</TableHead>
                      ))}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {movements.length === 0 && (
                      <TableRow>
                        <TableCell
                          colSpan={2 + allColumns.length}
                          className="text-center text-muted-foreground py-8"
                        >
                          No movements
                        </TableCell>
                      </TableRow>
                    )}
                    {movements.map((row, i) => (
                      <TableRow key={i}>
                        <TableCell>
                          {row._period
                            ? new Date(row._period as string).toLocaleString()
                            : "—"}
                        </TableCell>
                        <TableCell>
                          <Badge
                            variant={
                              row._movement_type === "RECEIPT" ? "success" : "destructive"
                            }
                          >
                            {row._movement_type as string}
                          </Badge>
                        </TableCell>
                        {allColumns.map((c) => (
                          <TableCell key={c.fieldName}>
                            {displayValue(c, row[c.columnName], row)}
                          </TableCell>
                        ))}
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="turnover">
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">Turnover Report</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex items-end gap-4 mb-4">
                <div className="grid gap-2">
                  <Label>From</Label>
                  <DatePicker
                    value={from}
                    onChange={setFrom}
                    includeTime
                  />
                </div>
                <div className="grid gap-2">
                  <Label>To</Label>
                  <DatePicker
                    value={to}
                    onChange={setTo}
                    includeTime
                  />
                </div>
                <Button onClick={loadTurnover}>Calculate</Button>
              </div>

              <div className="rounded-lg border overflow-hidden">
                <Table>
                  <TableHeader>
                    <TableRow>
                      {allColumns.map((c) => (
                        <TableHead key={c.fieldName}>{c.displayName}</TableHead>
                      ))}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {balances.length === 0 && (
                      <TableRow>
                        <TableCell
                          colSpan={allColumns.length}
                          className="text-center text-muted-foreground py-8"
                        >
                          Select a period and click Calculate
                        </TableCell>
                      </TableRow>
                    )}
                    {balances.map((row, i) => (
                      <TableRow key={i}>
                        {allColumns.map((c) => (
                          <TableCell key={c.fieldName}>
                            {displayValue(c, row[c.columnName], row)}
                          </TableCell>
                        ))}
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
