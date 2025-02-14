(ns wowman.catalog
  (:require
   [flatland.ordered.map :as omap]
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [java-time]
   [wowman
    ;;[core :as core]
    [utils :as utils :refer [todt utcnow]]
    [specs :as sp]
    [curseforge-api :as curseforge-api]
    [wowinterface-api :as wowinterface-api]]))

(defn expand-summary
  [addon-summary game-track]
  (let [dispatch-map {"curseforge" curseforge-api/expand-summary
                      "wowinterface" wowinterface-api/expand-summary
                      nil (fn [_ _] (error "malformed addon-summary:" (utils/pprint addon-summary)))}
        dispatch (get dispatch-map (:source addon-summary))]
    (try
      (dispatch addon-summary game-track)
      (catch Exception e
        (error e "unhandled exception attempting to expand addon summary")
        (error "please report this! https://github.com/ogri-la/wowman/issues")))))

;;

(defn-spec format-catalog-data ::sp/catalog
  "formats given catalog data"
  [addon-list ::sp/addon-summary-list, created-date ::sp/catalog-created-date, updated-date ::sp/catalog-updated-date]
  (let [addon-list (mapv #(into (omap/ordered-map) (sort %))
                         (sort-by :name addon-list))]
    {:spec {:version 1}
     :datestamp created-date
     :updated-datestamp updated-date
     :total (count addon-list)
     :addon-summary-list addon-list}))

(defn-spec write-catalog ::sp/extant-file
  "writes catalog to given `output-file` as json. returns path to the output file"
  [catalog-data ::sp/catalog, output-file ::sp/file]  ;; addon-list at this point has been ordered and doesn't resemble a hashmap anymore
  (utils/dump-json-file output-file catalog-data)
  (info "wrote" output-file)
  output-file)

;;

(defn de-dupe-wowinterface
  "at time of writing, wowinterface has 5 pairs of duplicate addons with slightly different labels
  for each pair we'll pick the most recently updated.
  these pairs *may* get picked up and filtered out further down when comparing merged catalogs,
  depending on the time difference between the two updated dates"
  [addon-list]
  (let [de-duper (fn [[_ group-list]]
                   (if (> (count group-list) 1)
                     (do
                       (warn "wowinterface: multiple addons slugify to the same :name" (utils/pprint group-list))
                       (last (sort-by :updated-date group-list)))
                     (first group-list)))
        addon-groups (group-by :name addon-list)]
    (mapv de-duper addon-groups)))

;;

(defn-spec -merge-catalogs ::sp/catalog
  [aa ::sp/catalog ab ::sp/catalog]
  (let [;; this is 80% sanity check, 20% correctness
        ab (assoc ab :addon-summary-list (de-dupe-wowinterface (:addon-summary-list ab)))

        addon-list (into (:addon-summary-list aa)
                         (:addon-summary-list ab))
        _ (info "total addons:" (count addon-list))

        addon-groups (group-by :name addon-list)
        _ (info "total unique addons:" (count addon-groups))

        ;; addons that appear in both catalogs
        multiple-sources (filter (fn [[_ group-list]]
                                   (> (count group-list) 1)) addon-groups)
        _ (info "total overlap:" (count multiple-sources) "(addons)" (count (flatten (vals multiple-sources))) "(entries)")

        ;; drop those addons that appear in multiple catalogs
        ;; they get some additional filtering
        multiple-sources-key-set (set (keys multiple-sources))
        ;; (this takes ages, there must be a faster way to remove ~1k rows from ~10k results)
        addon-list (remove #(some #{(:name %)} multiple-sources-key-set) addon-list)
        ;;_ (info "addons sans multiples:" (count addon-list))

        ;; when there is a very large gap between updated-dates, drop one addon in favour of the other
        ;; at time of writing:
        ;; - filtering for > 2 years removes  231 of the 2356 addons overlapping, leaving 2125 addons appearing in both catalogs
        ;; - filtering for > 1 year removes   338 of the 2356 addons overlapping, leaving 2018 addons appearing in both catalogs
        ;; - filtering for > 6 months removes 389 of the 2356 addons overlapping, leaving 1967 addons appearing in both catalogs
        ;; - filtering for > 1 month removes  471 of the 2356 addons overlapping, leaving 1885 addons appearing in both catalogs
        drop-some-addons (mapv (fn [[_ group-list]]

                                 ;; sanity check. it's definitely possible for an addon to appear more than twice
                                 (when (> (count group-list) 2)
                                   ;; huh, was expecting more than none
                                   ;; there should have been groupings *within* wowinterface ...
                                   ;; but if those groupings don't appear in curseforge, then that would make sense.
                                   ;; update: definitely the case after checking the duplicates within wowinterface.
                                   (error "addon has more than 2 entries in group:" (:name (first group-list)) (count group-list)))

                                 (let [[aa ab] (take 2 (sort-by #(-> % :description count) group-list)) ;; :description is nil for wowinterface
                                       adt (todt (:updated-date aa)) ;; wowinterface
                                       bdt (todt (:updated-date ab)) ;; curseforge (probably)
                                       diff (java-time/duration adt bdt)
                                       neg? (java-time/negative? diff)
                                       diff-days (java-time/as diff :days)
                                       diff-days (if neg? (- diff-days) diff-days)
                                       ;;threshold (* 365 2) ;; two years
                                       ;;threshold (/ 366 2) ;; six-ish months
                                       threshold 28 ;; 1 moonth. if an addon author hasn't updated both hosts within four weeks, then one is preferred over the other
                                       ]
                                   (if (> diff-days threshold)
                                     ;; if the diff is negative, that means B (curseforge) is older than A (wowinterface)
                                     (if neg?
                                       [aa]
                                       [ab])
                                     [aa ab])))
                               multiple-sources)
        filtered-group-addons (flatten drop-some-addons)
        _ (info "duplicate addons pruned after filtering for age:" (- (count (flatten (vals multiple-sources)))
                                                                      (count filtered-group-addons)))

        addon-list (into addon-list filtered-group-addons)
        _ (info "final addon count" (count addon-list) (format "(%s survived duplicate pruning)" (count filtered-group-addons)))

        today (utcnow)
        update-addon (fn [a]
                       (let [source (if-not (contains? a :description) :wowinterface :curseforge)
                             ;; an even more slugified label with hyphens and underscores removed
                             alt-name (-> a :label (slugify ""))
                             dtobj (java-time/zoned-date-time (:updated-date a))
                             age-in-days (java-time/as (java-time/duration dtobj today) :days)
                             version-age (condp #(< %2 %1) age-in-days
                                           7 :brand-new ;; < 1 week old
                                           42 :new      ;; 1-6 weeks old
                                           168 :recent  ;; 6 weeks-6 months old (28*6)
                                           504 :aging   ;; 6-18 months old (28*18)
                                           :ancient)

                             ;; todo: normalise categories here
                             ]
                         (merge a {:source source ;; json serialisation will stringify this :(
                                   :alt-name alt-name
                                   :age version-age})))
        addon-list (mapv update-addon addon-list)

        ;; should these two dates belong to the catalog proper or derived from the component catalogs?
        ;; lets go with derived for now
        created-date (first (sort [(:datestamp aa) (:datestamp ab)])) ;; earliest of the two catalogs
        updated-date (last (sort [(:updated-datestamp aa) (:updated-datestamp ab)]))] ;; most recent of the two catalogs

    (format-catalog-data addon-list created-date updated-date)))

(defn-spec shorten-catalog (s/or :ok ::sp/catalog, :problem nil?)
  [full-catalog-path ::sp/extant-file]
  (let [{:keys [addon-summary-list datestamp]}
        (utils/load-json-file-safely
         full-catalog-path
         :no-file? #(error (format "catalog '%s' could not be found" full-catalog-path))
         :bad-data? #(error (format "catalog '%s' is malformed and cannot be parsed" full-catalog-path))
         :invalid-data? #(error (format "catalog '%s' is incorrectly structured and will not be parsed" full-catalog-path))
         :data-spec ::sp/catalog)

        unmaintained? (fn [addon]
                        (let [dtobj (java-time/zoned-date-time (:updated-date addon))
                              release-of-previous-expansion (utils/todt "2016-08-30T00:00:00Z")]
                          (java-time/before? dtobj release-of-previous-expansion)))]
    (when addon-summary-list
      (format-catalog-data (remove unmaintained? addon-summary-list) datestamp datestamp))))

(defn-spec merge-catalogs ::sp/catalog
  [curseforge-catalog ::sp/extant-file, wowinterface-catalog ::sp/extant-file]
  (let [aa (utils/load-json-file curseforge-catalog)
        ab (utils/load-json-file wowinterface-catalog)]
    (-merge-catalogs aa ab)))

;;

(st/instrument)
