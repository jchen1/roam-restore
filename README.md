# roam-restore

Recover accidentally deleted Daily Notes pages

## Usage

1. Install Clojure
2. Download a HAR file of your Roam network traffic (on a fresh reload)
3. Modify `missing-date` in `roam_restore.clj`
4. Run `clj roam_restore.clj` from your command line
5. Make a new page in Roam for your deleted one
5. Paste the newly-generated `recovered.txt` into the new page 

## Notes

- It probably won't work for you - it works on my machine ¯\\_(ツ)_/¯

