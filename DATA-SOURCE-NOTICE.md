# Data Source and Compliance Notice

This application is an analytical screener, not an execution system and not a promise of profit.

Authenticated Stockbit private web endpoints provide completed daily OHLCV, traded value, transaction frequency, current-session orderbook OHLC, and broker summary. These are undocumented private endpoints, not a public or licensed Stockbit SDK.

The app discovers the current IDX equity catalogue through TradingView's live Indonesia scanner, with a seven-day local cache used only when discovery is temporarily unavailable. TradingView/IDX discovery data is not used to calculate strategy indicators. Stockbit is the market-data source for all strategy candles and price audits.

Free-float shares and historical orderbook replenishment are not used by any strategy because their evidence cannot currently be established reliably. UMA/FCA, historical bid-offer spread, and complete corporate-action status remain explicit global limitations. They are never fabricated or silently counted as strategy evidence.

Stockbit login opens Stockbit's own website inside an isolated WebView. The app does not read form fields, passwords, CAPTCHA, or trading PINs. It observes only allowlisted authentication-response URLs, stores the returned main-session tokens with Android Keystore, and validates the session against a read-only broker-directory request.

The connector relies on undocumented private web endpoints inferred from the open-source `stockbit-mcp` community package, which describes itself as unofficial and unaffiliated. It is not presented as an official Stockbit SDK. Stockbit may change, gate, rate-limit, or disable these endpoints. Failures leave the relevant market or broker data unavailable and never create fake data. Portfolio, order, PIN, and trading endpoints are not implemented.

These constraints prevent fake integrations, silent data substitution, and unsupported claims that a broker code identifies a specific market operator.
