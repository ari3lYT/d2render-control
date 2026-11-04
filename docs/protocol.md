**English** · [Русский](protocol.ru.md)

# Queue protocol

All paths are relative to the queue root. IDs match `[a-zA-Z0-9_-]{8,80}`. Requests are UTF-8 JSON.

| Path | Writer | Meaning |
| --- | --- | --- |
| `inbox/ID.json` | Mod | Fully published request |
| `inbox/ID.pending` | Mod | Uncommitted request; workers must ignore it |
| `cancel/ID` | Mod | Cancellation requested; worker decides the next safe boundary |
| `results/ID.json` | Worker | Result with a terminal or intermediate `stage` |
| `status.json` | Worker | `updated`, optional `active`, optional `recent` array |

Request variants:

```json
{"kind":"area","a":[-32,80,-32],"b":[32,80,32],"requested_by":"DemoOperator"}
```

```json
{"kind":"camera","camera":[0,100,0],"target":[32,70,32],"requested_by":"DemoOperator"}
```

```json
{"kind":"video","camera":[0,100,0],"end":[64,100,0],"target":[32,70,32],"seconds":4,"requested_by":"DemoOperator"}
```

Results use terminal stages `success`, `cancelled`, or `error`. Display fields include `id`, `stage`,
`frame`, `frames`, `spp`, `url` and `error`. Publish status/results atomically. Do not mark a real render
successful until its output is durable. Cancellation is advisory; the mod does not kill worker processes.
`tools/mock_worker.py` writes an explicit error noting that it produces no media; it validates wiring only.
Requests include the command actor's display name; keep production queue contents private.
