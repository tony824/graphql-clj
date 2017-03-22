(ns graphql-clj.schema-validator-test
  (:require [clojure.test :refer :all]
            [instaparse.core :as insta]
            [graphql-clj.parser :as parser]
            [graphql-clj.schema-validator :as schema-validator]))

(defn- err [msg sl sc si el ec ei]
  {:message msg :start {:line sl :column sc :index si} :end {:line el :column ec :index ei}})

;; macro to make writing validation tests easier, and also to give us
;; error line number
(defmacro def-validation-test [name schema & errors]
  `(deftest ~name
     (let [[~'errors _#] (-> (parser/parse-schema ~schema)
                             (schema-validator/validate-schema))]
       (is (~'= ~'errors [~@errors])))))

(def-validation-test duplicate-type-definition-type-type
  "type Dog {x:Int}
   type Dog {x:Int}"
  (err "type 'Dog' already declared" 2 4 20 2 20 36))

(def-validation-test duplicate-type-definition-type-interface
  "type Dog {x:Int}
   interface Dog {x:Int}"
  (err "type 'Dog' already declared" 2 4 20 2 25 41))

(def-validation-test duplicate-type-definition-type-input
  "type Dog {x:Int}
   input Dog {x:Int}"
  (err "type 'Dog' already declared" 2 4 20 2 21 37))

(def-validation-test duplicate-type-definition-type-union
  "type Dog {x:Int}
   union Dog = Dog"
  (err "type 'Dog' already declared" 2 4 20 2 19 35))

(def-validation-test duplicate-type-definition-type-enum
  "type Dog {x:Int}
   enum Dog { LAB }"
  (err "type 'Dog' already declared" 2 4 20 2 20 36))

(def-validation-test duplicate-type-definition-type-scalar
  "type Dog {x:Int}
   scalar Dog"
  (err "type 'Dog' already declared" 2 4 20 2 14 30))

;; We allow duplicate scalar definitions since they are harmless and
;; it also allows declaration of a scalar that is internally defined
(def-validation-test duplicate-scalars-are-okay
  "scalar URL
   scalar URL")

(def-validation-test duplicates
  "interface Cat {x:Int}
   type Cat {x:Int}
   type UnionMember {x:Int}
   union Pet = UnionMember
   type Pet {x:Int}
   enum Dog {LAB}
   type Dog {x:Int}"
  (err "type 'Cat' already declared" 2 4 25 2 20 41)
  (err "type 'Pet' already declared" 5 4 100 5 20 116)
  (err "type 'Dog' already declared" 7 4 138 7 20 154))

(def-validation-test duplicate-type-field
  "type Photo {
     width:Int
     width:Int
   }"
  (err "field 'width' already declared in 'Photo'" 3 6 33 3 15 42))

(def-validation-test duplicate-interface-field
  "interface Photo {
     width:Int
     width:Int
   }"
  (err "field 'width' already declared in 'Photo'" 3 6 38 3 15 47))

(def-validation-test duplicate-input-field
  "input Photo {
     width:Int
     width:Int
   }"
  (err "field 'width' already declared in 'Photo'" 3 6 34 3 15 43))

(def-validation-test duplicate-enum-constant
  "enum DogCommand {
     SIT
     DOWN
     SIT
   }"
  (err "enum constant 'SIT' already declared in 'DogCommand'" 4 6 42 4 9 45))

(def-validation-test duplicate-union-member
  "type Dog { x:Int }
   type Cat { x:Int }
   union Pet = Dog | Cat | Dog"
  (err "union member 'Dog' already declared in 'Pet'" 3 28 68 3 31 71))

(def-validation-test type-field-not-declared
  "type Dog { breed: Breed }"
  (err "type 'Breed' referenced by field 'breed' is not declared" 1 19 18 1 24 23))

(def-validation-test interface-field-not-declared
  "interface Pet { breed: Breed }"
  (err "type 'Breed' referenced by field 'breed' is not declared" 1 24 23 1 29 28))

(def-validation-test input-field-not-declared
  "input Cat { breed: Breed }"
  (err "type 'Breed' referenced by field 'breed' is not declared" 1 20 19 1 25 24))

(def-validation-test union-member-not-declared
  "union Pet = Cat"
  (err "union member 'Cat' is not declared" 1 13 12 1 16 15))

(def-validation-test union-member-is-not-a-type
  "interface Interface { x:Int }
   input Input { x:Int }
   union Pet = String | Interface | Input"
  (err "union member 'String' is not an object type" 3 16 70 3 22 76)
  (err "union member 'Interface' is not an object type" 3 25 79 3 34 88)
  (err "union member 'Input' is not an object type" 3 37 91 3 42 96))

;; Check that we can reference all introspection schema types
(def-validation-test introspection-references
  "type IntrospectionReferences {
     schema: __Schema
     types: [__Type]
     fields: [__Field]
     inputs: [__InputValue]
     enums: [__EnumValue]
     kinds: [__TypeKind]
     directives: [__Directive]
     dirloc: [__DirectiveLocation]
   }")  


(def-validation-test schema-with-multiple-schemas
  "type Dog { x : Int }
   schema { query: Dog }
   schema { query: Dog }"
  (err "schema is already declared" 3 4 49 3 25 70))

(def-validation-test schema-multiple-queries
  "type Dog { x : Int }
   schema {
     query: Dog
     query: Dog
   }"
  (err "'query' root is already declared" 4 6 54 4 16 64))

(def-validation-test schema-contains-query
  "schema {}"
  (err "schema must declare a query root type" 1 1 0 1 10 9))

(def-validation-test schema-multiple-mutations
  "type Dog { x : Int }
   schema {
     mutation: Dog
     query: Dog
     mutation: Dog
   }"
  (err "'mutation' root is already declared" 5 6 73 5 19 86))

(def-validation-test schema-type-not-declared
  "schema {
     query: Dog
   }"
  (err "'query' root type 'Dog' is not declared" 2 6 14 2 16 24))


(def-validation-test schema-type-is-not-allowed
  "schema {
     query: __Type
   }"
  (err "'query' root type cannot use a reserved type '__Type'" 2 6 14 2 19 27))

(def-validation-test schema-type-is-object-type
  "interface Dog { x : Int }
   schema { query: Dog }"
  (err "'query' root type 'Dog' must be an object type" 2 13 38 2 23 48))

(deftest schema-roots
  (let [[errs schema] (-> "type QRoot{x:Int} type MRoot{x:Int} schema { query: QRoot, mutation: MRoot }"
                          (parser/parse-schema)
                          (schema-validator/validate-schema))]
    (is (empty? errs))
    (is (= 'QRoot (get-in schema [:roots :query])))
    (is (= 'MRoot (get-in schema [:roots :mutation])))))

(deftest schema-default-roots
  (let [[errs schema] (-> "type Dog { x: Int }"
                          (parser/parse-schema)
                          (schema-validator/validate-schema))]
    (is (empty? errs))
    (is (= 'QueryRoot (get-in schema [:roots :query])))
    (is (nil? (get-in schema [:roots :mutation])))))
       
