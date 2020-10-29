# roam-restore

Recover accidentally deleted Daily Notes pages

## Usage

1. Install Clojure
2. Download a HAR file of your Roam network traffic (on a fresh reload)
3. Modify `missing-date` in `roam_restore.clj`
4. Run `clj roam_restore.clj` from your command line

## Notes

- It probably won't work for you - it works on my machine ¯\\_(ツ)_/¯
- Doesn't recover any block structure - you'll need to restore that yourself
