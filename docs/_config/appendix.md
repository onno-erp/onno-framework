## Enterprise connectors (`su.onno.enterprise`, separate repo)

Gated by `onno.<connector>.enabled` (e.g. `onno.tochka.enabled`, `onno.yookassa.enabled`). Each reads its
own `onno.<connector>.*` block (base URL, credentials/tokens, timeouts, retry, token cache). See the
[onno-enterprise](https://github.com/onno-erp/onno-enterprise) module READMEs.
