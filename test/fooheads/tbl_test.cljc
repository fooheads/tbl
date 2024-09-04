(ns fooheads.tbl-test
  {:clj-kondo/config '{:linters {:unresolved-symbol {:level :off}}}}
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [fooheads.tbl :as tbl
     :refer [interpret relation->tbl
             tabularize tbl table->tree template->tree-mapping
             tokenize transform]
     :include-macros true]
    [tick.core :as t]))


(deftest tokenize-test
  (testing "not resolving symbols"
    (is (= '[| :date        | :value  | :value2 |
             | t/date       |         |         |
             | ----------   | ------- | ------- |
             | "2021-07-01" | 10      | 100     |
             | "2021-07-02" | 20      |         |]

           (tokenize
             {:resolve false}
             | :date        | :value  | :value2 |
             | t/date       |         |         |
             | ----------   | ------- | ------- |
             | "2021-07-01" | 10      | 100     |
             | "2021-07-02" | 20      |         |))))

  #?(:clj
     (testing "resolving symbols"
       (is (= ['| :date        '| :value   '| :value2  '|
               '| #'t/date     '|          '|          '|
               '| '----------  '| '------- '| '------- '|
               '| "2021-07-01" '| 10       '| 100      '|
               '| "2021-07-02" '| 20       '| 'foo     '|]


              (tokenize
                {:resolve true}
                | :date        | :value  | :value2 |
                | t/date       |         |         |
                | ----------   | ------- | ------- |
                | "2021-07-01" | 10      | 100     |
                | "2021-07-02" | 20      | foo     |))))))


(deftest tabularize-test
  (testing "default options"
    (is (= '[[:date :value :value2]
             ---
             ["2021-07-01" 10 100]
             ["2021-07-02" 20 nil]]
           (->>
             (tokenize
               {}
               | :date        | :value | :value2 |
               | ---          | ---    | ---     |
               | "2021-07-01" | 10     | 100     |
               | "2021-07-02" | 20     |         |)
             (tabularize)))))


  (testing "! as separator"
    (is (= '[[:date :value :value2]
             ---
             ["2021-07-01" 10 100]
             ["2021-07-02" 20 nil]]

           (->>
             (tokenize
               {}
               ! :date        ! :value  ! :value2 !
               ! ---          ! ---     ! ---     !
               ! "2021-07-01" ! 10      ! 100     !
               ! "2021-07-02" ! 20      !         !)
             (tabularize {:separator '!})))))


  (testing "=== as divider"
    (is (= '[[:date :value :value2]
             ---
             ["2021-07-01" 10 100]
             ["2021-07-02" 20 nil]]

           (->>
             (tokenize
               {}
               | :date        | :value  | :value2 |
               | =====        | =====   | =====   |
               | "2021-07-01" | 10      | 100     |
               | "2021-07-02" | 20      |         |)
             (tabularize {:divider #"={2,}"})))))


  (testing "multiple values in cell should become a vector"
    (is (= '[[:simple :collections]
             ---
             [10 [1 2 3]]
             [X [A B C]]]
           (->>
             (tokenize
               {}
               | :simple | :collections |
               | ---     | -------      |
               | 10      | 1 2 3        |
               | X       | A B C        |)
             (tabularize))))))


(deftest interpret-test
  (testing "default options"
    (is (= {:col-headers [:date :value :value2]
            :data [["2021-07-01" 10 100]
                   ["2021-07-02" 20 nil]]}

           (->>
             (tokenize
               {}
               | :date        | :value  | :value2 |
               | ----------   | ------- | ------- |
               | "2021-07-01" | 10      | 100     |
               | "2021-07-02" | 20      |         |)
             (tabularize)
             (interpret)))))

  (testing "col-headers"
    (testing "1 (default)"
      (is (= {:col-headers '[A B]
              :data '[[x y]
                      [x nil]]}

             (interpret
               (tabularize
                 (tokenize
                   {}
                   | A   | B   |
                   | --- | --- |
                   | x   | y   |
                   | x   |     |))))))

    (testing "none"
      (is (= {:data '[[x y]
                      [x nil]]}

             (interpret
               {:col-header-idxs []}
               (tabularize
                 (tokenize
                   {}
                   | ------- | ------- |
                   | x       | y       |
                   | x       |         |))))))

    (testing "many"
      (is (= {:col-headers '[[A Mon 100] [B Mon 50]]
              :data '[[x y]
                      [x nil]]}

             (interpret
               {:col-header-idxs (range 3)}
               (tabularize
                 (tokenize
                   {}
                   | A       | B       |
                   | Mon     | Mon     |
                   | 100     | 50      |
                   | ------- | ------- |
                   | x       | y       |
                   | x       |         |)))))))

  #_#?(:clj
       (testing "coercions in table"
         (is (= {:col-headers [:date :value :value2]
                 :data [["2021-07-01" 10 100]
                        ["2021-07-02" 20 nil]]
                 :coercions {:date #'t/date
                             :value2 #'str}}

                (interpret
                  {:coercion-idx 1}
                  (tabularize
                    (tokenize
                      {:resolve true}
                      | :date        | :value  | :value2 |
                      | t/date       |         | str     |
                      | -----------  | ------- | ------- |
                      | "2021-07-01" | 10      | 100     |
                      | "2021-07-02" | 20      |         |)))))))

  (testing "row-headers"

    (testing "none (default)"
      (is (= {:data '[[x y]
                      [x nil]]}

             (interpret
               {:col-header-idxs []}
               '[---
                 [x y]
                 [x nil]])


             (interpret
               {:col-header-idxs []}
               (tabularize
                 (tokenize
                   {}
                   | --- | --- |
                   | x   | y   |
                   | x   |     |))))))


    (testing "one"
      (is (= {:row-headers '[A B]
              :data '[[x y]
                      [x nil]]}

             (->>
               (tokenize
                 {}
                 | --- | --- | --- |
                 | A   | x   | y   |
                 | B   | x   |     |)
               (tabularize)
               (interpret {:col-header-idxs []
                           :row-header-idxs (range 1)})))))


    (testing "many"
      (is (= {:row-headers '[[A Mon 100] [B Mon 50]]
              :data '[[x y]
                      [x nil]]}

             (->>
               (tokenize
                 {}
                 | --- | --- | --- | --- | --- |
                 | A   | Mon | 100 | x   | y   |
                 | B   | Mon | 50  | x   |     |)
               (tabularize)
               (interpret {:col-header-idxs []
                           :row-header-idxs [0 1 2]}))))))


  (testing "col-headers and row-headers"
    (is (= {:col-headers '[[A Mon 100] [B Mon 50]]
            :row-headers '[[a 1] [b 2]]
            :data '[[x y]
                    [x nil]]}

           (->>
             (tokenize
               {}
               |      |     | A       | B       |
               |      |     | Mon     | Mon     |
               |      |     | 100     | 50      |
               | ---- | --- | ------- | ------- |
               | a    | 1   | x       | y       |
               | b    | 2   | x       |         |)
             (tabularize)
             (interpret {:col-header-idxs [0 1 2]
                         :row-header-idxs [0 1]})))))

  (testing "without-divider"
    (is (= {:row-headers '[[A Mon 100] [B Mon 50]]
            :data '[[x y]
                    [x nil]]}

           (->>
             (tokenize
               {}
               | A   | Mon | 100 | x   | y   |
               | B   | Mon | 50  | x   |     |)
             (tabularize {:divider nil :width 5})
             (interpret
               {:col-header-idxs nil
                :row-header-idxs (range 3)}))))))


(deftest transform-test
  (testing "remove-blank-lines?"
    (is (= {:col-headers [:date :value :value2]
            :data [["2021-07-01" 10 100]
                   ["2021-07-02" 20 nil]]}
           (transform
             {:remove-blank-lines? true}
             {:col-headers [:date :value :value2]
              :data [["2021-07-01" 10 100]
                     [nil nil nil]
                     ["2021-07-02" 20 nil]]}))))

  (testing "coercions"
    (is (= {:col-headers [:date :value :value2]
            :data [[#time/date "2021-07-01" "10" 100]
                   [#time/date "2021-07-02" "20" nil]]}

           (transform
             {:coercions {:date t/date :value str}}
             {:col-headers [:date :value :value2]
              :data [["2021-07-01" 10 100]
                     ["2021-07-02" 20 nil]]}))))

  (testing "renames"
    (is (= {:col-headers [:date :value :degrees]
            :data [["2021-07-01" 10 100]
                   ["2021-07-02" 20 nil]]}

           (transform
             {:renames {:value2 :degrees}}
             {:col-headers [:date :value :value2]
              :data [["2021-07-01" 10 100]
                     ["2021-07-02" 20 nil]]}))))

  (testing "ns"
    (is (= {:col-headers [:foo/date :foo/value :foo/value2]
            :data [["2021-07-01" 10 100]
                   ["2021-07-02" 20 nil]]}

           (transform
             {:ns :foo}
             {:col-headers [:date :value :value2]
              :data [["2021-07-01" 10 100]
                     ["2021-07-02" 20 nil]]}))))

  (testing "format :map"
    (is (= {:col-headers [:date :value :value2]
            :data [["2021-07-01" 10 100]
                   ["2021-07-02" 20 nil]]}

           (transform
             {:format :map}
             {:col-headers [:date :value :value2]
              :data [["2021-07-01" 10 100]
                     ["2021-07-02" 20 nil]]}))))


  (testing "format :maps"
    (is (= [{:date "2021-07-01" :value 10 :value2 100}
            {:date "2021-07-02" :value 20 :value2 nil}]

           (transform
             {:format :maps}
             {:col-headers [:date :value :value2]
              :data [["2021-07-01" 10 100]
                     ["2021-07-02" 20 nil]]}))))


  (testing "format :relation"
    (is (= #{{:date "2021-07-01" :value 10 :value2 100}
             {:date "2021-07-02" :value 20 :value2 nil}}

           (transform
             {:format :relation}
             {:col-headers [:date :value :value2]
              :data [["2021-07-01" 10 100]
                     ["2021-07-02" 20 nil]]}))))

  (testing "format :table"
    (is (= [[:date :value :value2]
            ["2021-07-01" 10 100]
            ["2021-07-02" 20 nil]]

           (transform
             {:format :table}
             {:col-headers [:date :value :value2]
              :data [["2021-07-01" 10 100]
                     ["2021-07-02" 20 nil]]}))))

  (testing "format :cells"
    (is (= [{:value "2021-07-01" :col-header :date   :row-header 'a}
            {:value 10           :col-header :value  :row-header 'a}
            {:value 100          :col-header :value2 :row-header 'a}
            {:value "2021-07-02" :col-header :date   :row-header 'b}
            {:value 20           :col-header :value  :row-header 'b}
            {:value nil          :col-header :value2 :row-header 'b}]

           (transform
             {:format :cells}
             {:col-headers [:date :value :value2]
              :row-headers ['a 'b]
              :data [["2021-07-01" 10 100]
                     ["2021-07-02" 20 nil]]})))))


(deftest tbl-test
  (testing "happy case with different types"
    (is (= [{:attr-name 'group-id
             :attr-type :string
             :required true
             :description "The group id."}
            {:attr-name 'user-name
             :attr-type :string
             :required true
             :description "The user name"}]

           (tbl
             | :attr-name | :attr-type | :required | :description    |
             | ---------- | ---------- | --------- | ------------    |
             | 'group-id  | :string    | true      | "The group id." |
             | 'user-name | :string    | true      | "The user name" |))))


  (testing "Symbols can either be quoted or not"
    (is (= [{:attr-name 'group-id
             :attr-type :string}
            {:attr-name 'user-name
             :attr-type :string}]

           (tbl
             | :attr-name | :attr-type |
             | ---------- | ---------- |
             | 'group-id  | :string    |
             | user-name  | :string    |))))


  (testing "Symbols can optionally be resolved is possible"
    (is (= [{:attr-name 'group-id
             :attr-type :string}
            {:attr-name 'user-name
             :attr-type :string}]

           (tbl
             | :attr-name | :attr-type |
             | ---------- | ---------- |
             | 'group-id  | :string    |
             | 'user-name | :string    |))))


  (testing "last argument can be opts"
    (is (= #{{:date "2021-07-01" :value 10}
             {:date "2021-07-02" :value 20}}
           (tbl
             | :date        | :value     |
             | ----------   | ---------- |
             | "2021-07-01" | 10         |
             | "2021-07-02" | 20         |
             {:format :relation})))

    (is (= [{:date "2021-07-01" :value 10.0}
            {:date "2021-07-02" :value 20.0}]
           (tbl
             | :date        | :value     |
             | ----------   | ---------- |
             | "2021-07-01" | 10         |
             | "2021-07-02" | 20         |
             {:coercions {:value double}}))))


  (testing "cells"
    (testing "specific"
      (is (= '[{:col-header [A Mon 100] :row-header [a 1] :value x}
               {:col-header [B Mon 50] :row-header [a 1] :value y}
               {:col-header [A Mon 100] :row-header [b 2] :value x}
               {:col-header [B Mon 50] :row-header [b 2] :value nil}]

             (tbl
               |      |     | A       | B       |
               |      |     | Mon     | Mon     |
               |      |     | 100     | 50      |
               | ---- | --- | ------- | ------- |
               | a    | 1   | x       | y       |
               | b    | 2   | x       |         |
               {:col-header-idxs [0 1 2]
                :row-header-idxs [0 1]
                :format :cells}))))


    (testing "auto col-header-idxs"
      (is (= '[{:col-header [A Mon 100] :row-header [a 1] :value x}
               {:col-header [B Mon 50] :row-header [a 1] :value y}
               {:col-header [A Mon 100] :row-header [b 2] :value x}
               {:col-header [B Mon 50] :row-header [b 2] :value nil}]

             (tbl
               |      |     | A       | B       |
               |      |     | Mon     | Mon     |
               |      |     | 100     | 50      |
               | ---- | --- | ------- | ------- |
               | a    | 1   | x       | y       |
               | b    | 2   | x       |         |
               {:format :cells})))))


  (testing "two dashes in keyword"
    (is (= [{:attr/name :user/id}
            {:attr/name :user/first-name}
            {:attr/name :user/last-name}
            {:attr/name :user/full--name}]

           (tbl
             | :attr/name       |
             | -------          |
             | :user/id         |
             | :user/first-name |
             | :user/last-name  |
             | :user/full--name |))))


  #_(testing "second line can contain coercions"
      (is (= [{:date (t/date "2021-07-01") :value 10}
              {:date (t/date "2021-07-02") :value 20}]
             (tbl
               | :date        | :value     |
               | t/date       |            |
               | ----------   | ---------- |
               | "2021-07-01" | 10         |
               | "2021-07-02" | 20         |
               {:resolve true}))))

  #_(testing "coercions in header and opts are merged"
      (is (= [{:date (t/date "2021-07-01") :value 10.0 :value2 "100"}
              {:date (t/date "2021-07-02") :value 20.0 :value2 "200"}]
             (tbl
               | :date        | :value  | :value2 |
               | t/date       |         | str     |
               | ----------   | ------- | ------- |
               | "2021-07-01" | 10      | 100     |
               | "2021-07-02" | 20      | 200     |
               {:resolve true
                :coercions {:value double}})))))


(deftest relation->tbl-test
  (testing "default"
    (is
      (=
        (str/join
          "\n"
          ["(tbl"
           "  | :name | :date | :value |"
           "  | --- | --- | --- |"
           "  | \"Jimi\" | \"2021-07-01\" | 10 |"
           "  | \"Eddie\" | \"2021-07-02\" | 20 |)"])
        (relation->tbl
          [{:name "Jimi" :date "2021-07-01" :value 10} {:name "Eddie" :date "2021-07-02" :value 20}]))))

  (testing "with key sort order"
    (is
      (=
        (str/join
          "\n"
          ["(tbl"
           "  | :date | :name | :value |"
           "  | --- | --- | --- |"
           "  | \"2021-07-01\" | \"Jimi\" | 10 |"
           "  | \"2021-07-02\" | \"Eddie\" | 20 |)"])

        (relation->tbl
          [:date :name :value]
          [{:name "Jimi" :date "2021-07-01" :value 10} {:name "Eddie" :date "2021-07-02" :value 20}])))))


(def template
  (tbl
    {:format :table}
    | ---          | ---                 | ---                  |
    | Name         | :artist/name        |                      |
    | Formed       | :artist/formed-year | :artist/formed-month |
    |              |                     |                      |
    | AlbumTitle   | ReleaseYear         |                      |
    | :albums      |                     |                      |
    | :album/title | :album/release-year |                      |
    |              |                     | TrackName            |
    |              |                     | :tracks              |
    |              |                     | :track/name          |
    |              |                     |                      |
    |              |                     | PerformerName        |
    |              |                     | :performers          |
    |              |                     | :performer/name      |))


(deftest table->tree-test
  (is
    (=
      {:artist/name "Led Zeppelin"
       :artist/formed-year 1968
       :artist/formed-month nil
       :albums
       [{:album/title "I"
         :album/release-year 1968
         :tracks
         [{:track/name "Good Times Bad Times"}
          {:track/name "Dazed and Confused"}]}
        {:album/title "IV"
         :album/release-year 1971
         :tracks
         [{:track/name "Black Dog"}
          {:track/name "Rock and Roll"}]
         :performers
         [{:performer/name "Robert Plant"}
          {:performer/name "John Bonham"}]}]}

      (table->tree
        template
        (tbl
          {:format :table}
          | ---        | ---            | ---                    |
          | Formed     | 1968           |                        |
          | Name       | "Led Zeppelin" |                        |
          |            |                |                        |
          | AlbumTitle | ReleaseYear    |                        |
          | "I"        | 1968           |                        |
          |            |                | TrackName              |
          |            |                | "Good Times Bad Times" |
          |            |                | "Dazed and Confused"   |
          |            |                |                        |
          | "IV"       | 1971           |                        |
          |            |                | TrackName              |
          |            |                | "Black Dog"            |
          |            |                | "Rock and Roll"        |
          |            |                |                        |
          |            |                | PerformerName          |
          |            |                | "Robert Plant"         |
          |            |                | "John Bonham"          |)))))


(deftest template->tree-mapping-test
  (is (= {:artist/formed-month :artist/formed-month
          :artist/formed-year :artist/formed-year
          :artist/name :artist/name
          :albums
          [{:album/title :album/title
            :album/release-year :album/release-year
            :tracks
            [{:track/name :track/name}]
            :performers
            [{:performer/name :performer/name}]}]}

         (template->tree-mapping template))))

