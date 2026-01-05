# Signal Change Radar

A local, user-friendly Binance spot monitor that alerts when a watched pair moves past your per-window thresholds across 1m, 5m, 15m, 1h, 1d, 1w, and 1mo windows.

## How to run

Option 1 (quick):
- Open `index.html` in your browser.

Option 2 (recommended for reliability):
- In this folder, run:
  - `python -m http.server 8000`
- Visit `http://localhost:8000` in your browser.

## Tips

- Use commas or line breaks to enter symbols.
- If you type a base asset (example: BTC), the app will append the selected quote (example: USDT).
- To force a specific pair, type it as `BTCUSDT` or `BTC/USDT`.
- Set a different percent threshold for each window to control alert sensitivity.

## Notes

- Alerts use Binance public WebSocket streams and do not require an API key.
- If a symbol does not exist on Binance, it will stay blank in the table.
