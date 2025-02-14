(ns wowman.catalog-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman
    [catalog :as catalog]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   ))

(deftest de-dupe-wowinterface
  (testing "given multiple addons with the same name, the most recently updated one is preferred"
    (let [fixture [{:name "adibags" :updated-date "2001-01-01T00:00:00Z" :source "wowinterface"}
                   {:name "adibags" :updated-date "2019-09-09T00:00:00Z" :source "curseforge"}]
          expected [{:name "adibags" :updated-date "2019-09-09T00:00:00Z" :source "curseforge"}]]
      (is (= (catalog/de-dupe-wowinterface fixture) expected))))

  (testing "given multiple addons with distinct names, all addons are returned"
    (let [fixture [{:name "adi-bags" :updated-date "2001-01-01T00:00:00Z" :source "wowinterface"}
                   {:name "baggy-adidas" :updated-date "2019-09-09T00:00:00Z" :source "curseforge"}]
          expected fixture]
      (is (= (catalog/de-dupe-wowinterface fixture) expected)))))

(deftest format-catalog-data
  (testing "catalog data has a consistent structure"
    (let [addon-list []
          created "2001-01-01"
          updated created
          expected {:spec {:version 1}
                    :datestamp created
                    :updated-datestamp updated
                    :total 0
                    :addon-summary-list addon-list}]
      (is (= (catalog/format-catalog-data addon-list created updated) expected)))))

(deftest merge-catalogs
  (testing "dates are correct after a merge"
    (let [aa {:datestamp "2001-01-01" :updated-datestamp "2001-01-02" :spec {:version 1} :addon-summary-list [] :total 0}
          ab {:datestamp "2001-01-03" :updated-datestamp "2001-01-04" :spec {:version 1} :addon-summary-list [] :total 0}
          expected {:spec {:version 1}
                    :datestamp "2001-01-01"
                    :updated-datestamp "2001-01-04"
                    :total 0
                    :addon-summary-list []}]
      (is (= (catalog/-merge-catalogs aa ab) expected)))))
