# SQL — be-batch (tabelle `batch_*` nello schema `db_base`)

be-batch **non ha uno schema proprio**: le sue tabelle (`batch_definition`, `batch_subscription`,
`batch_execution`, …) vivono in **`db_base`**.

👉 **Prerequisito**: eseguire prima gli script di `be-base/sql` (creano lo schema e le tabelle base).

## Ordine di esecuzione

| # | Script | Contenuto |
|---|--------|-----------|
| 01 | `01_batch_schema.sql` | crea le tabelle `batch_*` |
| 02→05 | incrementali | voce di menu, credenziali cifrate, `start_at`, cron nullable |
| 06→08 | seed | definizioni dei job disponibili (report AI, alert Pibisi) |

```bash
for f in be-batch/sql/[0-9]*.sql; do mysql -u <user> -p < "$f"; done
```

I seed 06→08 registrano i job schedulabili: senza, la pagina *Impostazioni → Schedulazioni batch*
non propone nulla da schedulare.

⚠️ Le credenziali di servizio delle schedulazioni sono cifrate con `BATCH_CRED_SECRET`/`BATCH_CRED_SALT`:
su un ambiente nuovo vanno reinserite dall'interfaccia, non copiate da un altro ambiente.
