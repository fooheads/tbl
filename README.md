# tbl

A Clojure and ClojureScript library for creating and manipulating literal
tables using a DSL syntax.

## Overview

`tbl` provides a convenient way to define tabular data directly in your
Clojure code using a table-like syntax with pipes (`|`) as separators.
It's particularly useful for:

- Creating test data
- Defining configuration tables
- Working with structured data in a readable format
- Converting between different data representations (maps, relations, tables, etc.)

## Quick Start

```clojure
(require '[fooheads.tbl :refer [tbl]])

;; Create a simple table that returns a vector of maps
(tbl
  | :name     | :age | :city      |
  | --------- | ---- | ---------- |
  | "Alice"   | 30   | "New York" |
  | "Bob"     | 25   | "London"   |
  | "Charlie" | 35   | "Tokyo"    |)

;; Returns:
;; [{:name "Alice", :age 30, :city "New York"}
;;  {:name "Bob", :age 25, :city "London"}
;;  {:name "Charlie", :age 35, :city "Tokyo"}]
```
## The library

[![Clojars Project](https://img.shields.io/clojars/v/com.fooheads/tbl.svg)](https://clojars.org/com.fooheads/tbl)

## Features

### Basic Table Creation

The `tbl` macro creates tables using a pipe-separated syntax:

```clojure
(tbl
  | :column1 | :column2 | :column3 |
  | -------- | -------- | -------- |
  | value1   | value2   | value3   |
  | value4   | value5   | value6   |)
```

### Multiple Output Formats

Control the output format using the `:format` option:

```clojure
;; Vector of maps (default)
(tbl
  | :name | :value |
  | ----- | ------ |
  | "A"   | 1      |
  | "B"   | 2      |)
;; => [{:name "A", :value 1} {:name "B", :value 2}]

;; Set (relation)
(tbl
  | :name | :value |
  | ----- | ------ |
  | "A"   | 1      |
  | "B"   | 2      |
  {:format :relation})
;; => #{{:name "A", :value 1} {:name "B", :value 2}}

;; Table format (vector of vectors)
(tbl
  | :name | :value |
  | ----- | ------ |
  | "A"   | 1      |
  | "B"   | 2      |
  {:format :table})
;; => [[:name :value] ["A" 1] ["B" 2]]

;; Map format
(tbl
  | :name | :value |
  | ----- | ------ |
  | "A"   | 1      |
  | "B"   | 2      |
  {:format :map})
;; => {:col-headers [:name :value], :data [["A" 1] ["B" 2]]}
```

### Data Transformations

#### Type Coercions

Apply functions to transform column values:

```clojure
(tbl
  | :date        | :amount |
  | ------------ | ------- |
  | "2021-07-01" | "10.50" |
  | "2021-07-02" | "20.75" |
  {:coercions {:date #(java.time.LocalDate/parse %)
               :amount #(Double/parseDouble %)}})
;; => [{:amount 10.5 :date #time/date "2021-07-01"}
;;     {:amount 20.75 :date #time/date "2021-07-02"}})
```

#### Column Renaming

Rename columns using the `:renames` option:

```clojure
(tbl
  | :old-name | :value |
  | --------- | ------ |
  | "A"       | 1      |
  {:renames {:old-name :new-name}})
;; => [{:new-name "A", :value 1}]
```

#### Namespace Qualification

Add a namespace to all column keywords:

```clojure
(tbl
  | :name | :value |
  | ----- | ------ |
  | "A"   | 1      |
  {:ns :user})
;; => [{:user/name "A", :user/value 1}]
```

### Working with Symbols

Symbols in tables are automatically quoted, but you can control this behavior:

```clojure
;; Symbols are quoted by default
(tbl
  | :symbol | :value |
  | ------- | ------ |
  | my-sym  | 1      |)
;; => [{:symbol 'my-sym, :value 1}]

;; Unquote symbols to get their values
(def my-var "hello")
(tbl
  | :symbol | :value |
  | ------- | ------ |
  | my-var  | 1      |
  {:unquote true})
;; => [{:symbol "hello", :value 1}]
```

### Complex Data Structures

#### Multi-value Cells

Cells can contain multiple values, which become vectors:

```clojure
(tbl
  | :name | :vals |
  | ----- | ------|
  | "A"   | 1 2   |
  | "B"   | 3 4 5 |)
;; => [{:name "A", :vals [1 2]}
;;     {:name "B", :vals [3 4 5]}]
```

#### Row and Column Headers

Support for complex table structures with both row and column headers:

```clojure
(tbl
  |      |     | col1 | col2 |
  | ---- | --- | ---- | ---- |
  | row1 | 1   | A    | B    |
  | row2 | 2   | C    | D    |
  {:format :cells})
;; => ({:col-header col1 :row-header [row1 1] :value A}
;;     {:col-header col2 :row-header [row1 1] :value B}
;;     {:col-header col1 :row-header [row2 2] :value C}
;;     {:col-header col2 :row-header [row2 2] :value D})
```

### Tree Structures

Convert tabular data into nested tree structures using templates:

```clojure
;; Define a template
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

(def data-table
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
    |            |                | "John Bonham"          |))


;; Apply template to data
(table->tree template data-table)

;; => {:artist/name "Led Zeppelin"
;;     :artist/formed-year 1968
;;     :artist/formed-month nil
;;     :albums
;;     [{:album/title "I"
;;       :album/release-year 1968
;;       :tracks
;;       [{:track/name "Good Times Bad Times"}
;;        {:track/name "Dazed and Confused"}]}
;;      {:album/title "IV"
;;       :album/release-year 1971
;;       :tracks
;;       [{:track/name "Black Dog"}
;;        {:track/name "Rock and Roll"}]
;;       :performers
;;       [{:performer/name "Robert Plant"}
;;        {:performer/name "John Bonham"}]}])
```

## Utility Functions

### Converting Relations to Tables

Generate table code from existing data:

```clojure
(relation->tbl
  [{:name "Alice" :age 30}
   {:name "Bob" :age 25}])
;; Returns a string with tbl macro code
```

### Low-level Functions

- `tokenize` - Convert table syntax to tokens
- `tabularize` - Convert tokens to table structure
- `interpret` - Extract headers and data from table
- `transform` - Apply transformations to interpreted data

## Configuration Options

### Table Parsing Options

- `:separator` - Token used between columns (default: `'|`)
- `:divider` - Pattern for divider rows (default: `#"-{3,}"`)
- `:width` - Explicit table width (auto-detected by default)

### Interpretation Options

- `:col-header-idxs` - Which rows are column headers (default: `:auto`)
- `:row-header-idxs` - Which columns are row headers (default: `:auto`)
- `:coercion-idx` - Row containing type coercion functions

### Transformation Options

- `:remove-blank-lines?` - Remove rows with all nil values
- `:coercions` - Map of column to transformation function
- `:renames` - Map for renaming columns
- `:ns` - Namespace to apply to all column keywords
- `:format` - Output format (`:maps`, `:relation`, `:table`, `:map`, `:cells`)
- `:unquote` - Whether to unquote symbols (default: `false`)

## Examples

### Test Data Creation

```clojure
(def users
  (tbl
    | :id | :name     | :email              | :active |
    | --- | --------- | ------------------- | ------- |
    | 1   | "Alice"   | "alice@example.com" | true    |
    | 2   | "Bob"     | "bob@example.com"   | false   |
    | 3   | "Charlie" | "charlie@example.com" | true   |))
```

### Configuration Tables

```clojure
(def config
  (tbl
    | :env   | :database-url           | :port |
    | ------ | ----------------------- | ----- |
    | :dev   | "jdbc:h2:mem:testdb"    | 3000  |
    | :test  | "jdbc:h2:mem:testdb"    | 3001  |
    | :prod  | "jdbc:postgresql://..." | 8080  |
    {:format :relation}))
```

## License

This code in this repo is distributed under the Eclipse Public License, the same as Clojure.

