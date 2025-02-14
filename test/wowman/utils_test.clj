(ns wowman.utils-test
  (:require
   ;;[taoensso.timbre :refer [debug info warn error spy]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman.utils :as utils :refer [join]]
   [me.raynes.fs :as fs]))

(def ^:dynamic *temp-dir-path* "")

(defn tempdir-fixture
  "each test has a temporary dir available to it"
  [f]
  (binding [*temp-dir-path* (str (fs/temp-dir "wowman.utils-test."))]
    (f)
    (fs/delete-dir *temp-dir-path*)))

(use-fixtures :once tempdir-fixture)

(deftest format-interface-version
  (testing "integer interface version converted to dot-notation correctly"
    (let [cases
          ["00000" "0.0.0"
           "10000" "1.0.0"
           "00100" "0.1.0"
           "00001" "0.0.1"

           ;; actual cases
           "00304" "0.3.4" ;; first pre-release
           "10000" "1.0.0" ;; first release
           "20001" "2.0.1" ;; Burning Crusade, Before The Storm
           "30002" "3.0.2" ;; WotLK, Echos of Doom
           "30008a" "3.0.8a" ;; 'a' ?? supported, but eh ...

            ;; ambiguous/broken cases
           "00010" "0.0.0"
           "01000" "0.0.0"
           "10100" "1.1.0" ;; ambiguous, also, 1.10.0, 10.1.0, 10.10.0
           "10123" "1.1.3" ;; last patch of 1.x, should be 1.12.3

           ;; no match, return nil
           "" nil
           "0" nil
           "00" nil
           "000" nil
           "0000" nil
           "a" nil
           "aaaaa" nil
           "!" nil
           "!!!!!" nil]]

      (doseq [[case expected] (partition 2 cases)]
        (testing (str "testing " case " expecting: " expected)
          (is (= expected (utils/interface-version-to-game-version case))))))))

(deftest merge-lists
  (testing "the two lists are just merged when there are no matches"
    (let [a [{:id "bar"}]
          b [{:id "baz"}]
          expected [{:id "bar"} {:id "baz"}]]
      (is (= expected (utils/merge-lists :id a b)))))

  (testing "when simple merging happens, order is preserved and items in list b replace their counterparts in list a"
    (let [a [{:id "x" :val true} {:id "y" :val true} {:id "z" :val true}]
          b [{:id "z" :foo "bar"} {:id "x" :val false}]
          expected [{:id "x" :val false} {:id "y" :val true} {:id "z" :foo "bar"}]]
      (is (= expected (utils/merge-lists :id a b)))))

  (testing "an empty list for b does nothing"
    (let [a [{:id "x" :val true} {:id "y" :val true} {:id "z" :val true}]
          b []
          expected [{:id "x" :val true} {:id "y" :val true} {:id "z" :val true}]]
      (is (= expected (utils/merge-lists :id a b)))))

  (testing "an empty list for a does nothing"
    (let [a []
          b [{:id "x" :val true} {:id "y" :val true} {:id "z" :val true}]
          expected [{:id "x" :val true} {:id "y" :val true} {:id "z" :val true}]]
      (is (= expected (utils/merge-lists :id a b)))))

  (testing "the entries from list b can prepended if a truth-y :prepend? flag is passed in"
    (let [a [{:id "bar"}]
          b [{:id "baz"}]
          expected [{:id "baz"} {:id "bar"}]]
      (is (= expected (utils/merge-lists :id a b :prepend? true))))))

(deftest semver-sort
  (testing "basic sort"
    (let [given ["1.2.3" "4.11.6" "4.2.0" "1.5.19" "1.5.5" "4.1.3" "1.2" "2.3.1" "10.5.5" "1.6.0" "1.2.3" "11.3.0" "1.2.3.4" "1.6.0-unstable" "1.6.0-aaaaaa"]
          ;; sort order for the '-something' cases are unspecified. depends entirely on input order
          expected '("1.2" "1.2.3" "1.2.3" "1.2.3.4" "1.5.5" "1.5.19" "1.6.0" "1.6.0-unstable" "1.6.0-aaaaaa" "2.3.1" "4.1.3" "4.2.0" "4.11.6" "10.5.5" "11.3.0")]
      (is (= expected (utils/sort-semver-strings given))))))

(deftest from-epoch
  (testing "a ymd string is returned from an epoch Long without ms precision"
    (is (= (utils/from-epoch 0) "1970-01-01T00:00:00Z"))
    (is (= (utils/from-epoch 1504050180) "2017-08-29T23:43:00Z"))
    (is (= (utils/from-epoch 1207377654) "2008-04-05T06:40:54Z"))))

(deftest days-between-then-and-now
  (testing "the number of days between two dates"
    (java-time/with-clock (java-time/fixed-clock "2001-01-02T00:00:00Z")
      (is (= (utils/days-between-then-and-now "2001-01-01") 1)))))

(deftest file-older-than
  (testing "files whose modification times are older than N hours"
    (java-time/with-clock (java-time/fixed-clock "1970-01-01T02:00:00Z") ;; jan 1st 1970, 2 am
      (let [path (utils/join *temp-dir-path* "foo")]
        (try
          (fs/touch path 0) ;; created Jan 1st 1970
          (.setLastModified (fs/file path) 0) ;; modified Jan 1st 1970
          (is (utils/file-older-than path 1))
          (is (not (utils/file-older-than path 3)))
          (finally
            (fs/delete path)))))))
