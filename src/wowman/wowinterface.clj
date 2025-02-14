(ns wowman.wowinterface
  (:require
   [clojure.string :refer [trim]]
   [clojure.set]
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [wowman
    [specs :as sp]
    [utils :as utils :refer [to-uri]]
    [http :as http]]
   [flatland.ordered.map :as omap]
   [net.cgrand.enlive-html :as html :refer [html-snippet select]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [java-time]
   [java-time.format]))

(def host "https://www.wowinterface.com/downloads/")

(def category-pages {"cat23.html" "Stand-Alone addons"
                     "cat39.html" "Class & Role Specific"
                     "cat109.html" "Info, Plug-in Bars"
                     "cat158.html" "Classic - General"})

(defn format-wowinterface-dt
  "formats a shitty US-style m/d/y date with a shitty 12 hour time component and no timezone
  into a glorious RFC3399 formatted UTC string."
  [dt]
  (let [dt (java-time/local-date-time "MM-dd-yy hh:mm a" dt) ;; "09-07-18 01:27 PM" => obj with no tz
        ;; no tz info available on site, assume utc
        dt-utc (java-time/zoned-date-time dt "UTC") ;; obj with no tz => utc obj
        fmt (get java-time.format/predefined-formatters "iso-offset-date-time")]
    (java-time/format fmt dt-utc)))

;;

(defn-spec expand-summary (s/or :ok ::sp/addon, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. one additional look-up per ::addon required"
  [addon-summary ::sp/addon-summary]
  (let [message (str "downloading summary data: " (:name addon-summary))
        data (http/download (:uri addon-summary) :message message)
        detail-html (html-snippet data)
        version (-> detail-html (select [:#author :#version html/content]) first (subs (count "Version: ")))
        addon-id (-> addon-summary :uri (clojure.string/replace #"\D*" "")) ;; https://.../info21651 => 21651

        ;; fun fact: download-uri can be almost anything. for example "https://cdn.wowinterface.com/downloads/file<addon-id>/whatever.zip" works
        ;; we'll play nicely though, and use it as intended
        slugified-label (-> addon-summary :label (slugify "_"))
        download-uri (format "https://cdn.wowinterface.com/downloads/file%s/%s-%s.zip" addon-id slugified-label version)

        ;; only available on addons that support the current wow version (I think?)
        ;; which means with the next release the value will disappear unless we hold on to it? urgh.
        ;; at least it's available from the toc file
        iface-version (some-> detail-html (select [:#patch :abbr html/content]) first utils/game-version-to-interface-version)
        iface-version (when iface-version {:interface-version iface-version})

        updates {:download-uri download-uri
                 :version version
                 ;;:donation-uri ;; not available in any structured or consistent way :(
                 }]
    (merge addon-summary updates iface-version)))

;;

(defn parse-category-list
  [category-page]
  (let [snippet (-> host (str category-page) http/download html-snippet)
        cat-list (-> snippet (select [:div#colleft :div.subcats :div.subtitle :a]))
        final-url (fn [href]
                    ;; converts the href that looks like '/downloads/cat19.html' to '/downloads/index.php?cid=19"
                    (let [cat-id (str "index.php?cid=" (clojure.string/replace href #"\D*" "")) ;; index.php?cid=19
                          sort-by "&sb=dec_date" ;; updated date, most recent to least recent
                          another-sort-by "&so=desc" ;; most to least recent. must be consistent with `sort-by` prefix
                          pt "&pt=f" ;; nfi but it's mandatory
                          page "&page=1"] ;; not necessary, and `1` is default. we'll add it here to avoid a cache miss later
                      (str host, cat-id, sort-by, another-sort-by, pt, page)))
        extractor (fn [cat]
                    {:label (-> cat :content first)
                     :url (-> cat :attrs :href final-url)})]
    (debug (format "%s categories found" (count cat-list)))
    (mapv extractor cat-list)))

(defn extract-source-id
  [a]
  ;; fileinfo.php?s=c33edd26881a6a6509fd43e9a871809c&amp;id=23145 => 23145
  (-> a :attrs :href (clojure.string/split #"&.+=") last Integer.))

(defn extract-addon-uri
  [a]
  (str host "info" (extract-source-id a)))

(defn extract-addon-summary
  [snippet]
  (try
    (let [extract-updated-date #(format-wowinterface-dt
                                 (-> % (subs 8) trim)) ;; "Updated 09-07-18 01:27 PM " => "09-07-18 01:27 PM"
          anchor (-> snippet (select [[:a (html/attr-contains :href "fileinfo")]]) first)
          label (-> anchor :content first trim)]
      {:uri (extract-addon-uri anchor)
       :name (-> label slugify)
       :label label
       :source-id (extract-source-id anchor)
       ;;:description nil ;; not available in summary
       ;;:category-list [] ;; not available in summary, added by caller
       ;;:created-date nil ;; not available in summary
       :updated-date (-> snippet (select [:div.updated html/content]) first extract-updated-date)
       :download-count (-> snippet (select [:div.downloads html/content]) first (clojure.string/replace #"\D*" "") Integer.)})
    (catch RuntimeException re
      (error re (format "failed to scrape snippet with '%s', excluding from results: %s" (.getMessage re) (utils/pprint snippet)))
      nil)))

(defn scrape-addon-page
  [category page-num]
  (let [url (clojure.string/replace (:url category) #"page=\d+" (str "page=" page-num))
        page-content (-> url http/download html-snippet)
        addon-list (-> page-content (select [:#filepage :div.file]))
        extractor (fn [snippet]
                    (assoc (extract-addon-summary snippet) :category-list #{(:label category)}))]
    (mapv extractor addon-list)))

(defn scrape-category-page-range
  [category]
  (let [;; extract the number of results from the page navigation
        page-content (-> category :url http/download html-snippet)
        page-nav (-> page-content (select [:.pagenav [:td.alt1 html/last-of-type] :a]))
        ;; just scrape first page when page-nav is empty
        page-count (if (empty? page-nav) 1 (-> page-nav first :attrs :href
                                               (clojure.string/split #"=") last Integer.))
        page-range (range 1 (inc page-count))]
    page-range))

(defn scrape-category-page
  [category]
  (info (:label category))
  (let [;; sub-category pages handled in `scrape`
        skippable (vals category-pages)] ;; ["Class & Role Specific", ...]
    (if (some #{(:label category)} skippable)
      []
      (let [extractor (partial scrape-addon-page category)
            page-range (scrape-category-page-range category)]
        (info (format "scraping %s pages in '%s'" (last page-range) (:label category)))
        (flatten (mapv extractor page-range))))))

(defn download-parse-filelist-file
  "returns a map of wowinterface addons, keyed by their :source-id (as a string).
  wowinterface.com has a single large file with all/most of their addon data in it called 'filelist.json'.
  the addon details endpoint is missing supported versions of wow it in.
  Instead that data is in this list and must be incorporated in the catalog."
  []
  (let [url "https://api.mmoui.com/v3/game/WOW/filelist.json"
        resp (http/download url)
        file-details (utils/from-json resp)
        file-details (mapv (fn [addon]
                             (update addon :UID #(Integer/parseInt %))) file-details)]
    (group-by :UID file-details)))

(defn expand-addon-with-filelist
  [filelist addon]
  (let [filelist-addon (first (get filelist (:source-id addon)))
        ;; supported game version is not the same as game track ('classic' or 'retail')
        ;; wowinterface conflates the two (or am I splitting hairs?)
        ;; if 'WoW Classic' is found, then the 'classic' game track is supported
        ;; if more results are found, retail is supported as well
        compatibility (->> filelist-addon :UICompatibility (map :name) set)
        many-results? (> (count compatibility) 1)
        wowi-classic "WoW Classic"

        mapping {[wowi-classic true]  #{"classic" "retail"}
                 [wowi-classic false] #{"classic"}
                 [nil true] #{"retail"}
                 [nil false] #{"retail"}}

        key [(some #{wowi-classic} compatibility) many-results?]]
    (assoc addon :game-track-list (get mapping key))))

(defn scrape
  [output-path]
  (let [category-pages (keys category-pages) ;; [cat23.html, ...]
        category-list (flatten (mapv parse-category-list category-pages))
        addon-list (flatten (mapv scrape-category-page category-list))

        ;; an addon may belong to many categories
        ;; group addons by their :label (guaranteed to be unique) and then merge the categories together
        addon-groups (group-by :label addon-list)
        addon-list (for [[_ group-list] addon-groups
                         :let [addon (first group-list)]]
                     (assoc addon :category-list
                            (reduce clojure.set/union (map :category-list group-list))))

        filelist (download-parse-filelist-file)

        ;; there are 186 (at time of writing) addons scraped from the site that are not present in the filelist.json file.
        ;; these appear to be discontinued/obsolete/beta-only/'removed at author's request'/etc type addons.
        ;; this removes those addons from the addon-list
        addon-list (filter (fn [addon]
                             (get filelist (:source-id addon))) addon-list)

        ;; moosh extra data into each addon from the filelist
        addon-list (mapv (partial expand-addon-with-filelist filelist) addon-list)

        ;; ensure addon keys are ordered for better diffs
        addon-list (mapv #(into (omap/ordered-map) (sort %)) addon-list)

        ;; the addons themselves should be ordered now. alphabetically I suppose
        addon-list (sort-by :label addon-list)]
    (spit output-path (utils/to-json {:spec {:version 1}
                                      :datestamp (utils/datestamp-now-ymd)
                                      :updated-datestamp (utils/datestamp-now-ymd)
                                      :total (count addon-list)
                                      :addon-summary-list addon-list}))
    output-path))

;;

(st/instrument)
