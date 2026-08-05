(ns glimmer.ratom-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.ratom :as r :refer [atom cursor reaction]]))

;; NOTE: requiring glimmer.ratom rebinds clojure.core/deref, reset! and swap!
;; so @, reset! and swap! work uniformly over reactive cells and plain host
;; atoms (the rebound ops delegate to the host for non-reactive values). The
;; plain atoms created below via clojure.core/atom therefore still behave.

(deftest ratom-read-and-write
  (testing "an atom reads its initial value"
    (is (= 0 @(atom 0))))
  (testing "reset! updates the value"
    (let [a (atom :a)]
      (reset! a :b)
      (is (= :b @a))))
  (testing "swap! applies a function"
    (let [a (atom 5)]
      (swap! a inc)
      (is (= 6 @a))
      (swap! a + 10 20)
      (is (= 36 @a)))))

(deftest host-atoms-still-work
  ;; host atoms must keep working through the rebound @ / reset! / swap!.
  (let [a (clojure.core/atom 1)]
    (is (= 1 @a))
    (reset! a 2)
    (is (= 2 @a))
    (swap! a * 3)
    (is (= 6 @a))))

(deftest swap-preserves-nil-args
  ;; swap! used to inject a nil in its 2-arity and strip nils back out in the
  ;; varargs arity, which silently dropped LEGITIMATE nil arguments:
  ;; (swap! a assoc :k nil) became (assoc @a :k) — an odd key/val count. The
  ;; reconciler storing a nil :key for an unkeyed child hit this on every render.
  (testing "host atom"
    (let [a (clojure.core/atom {})]
      (is (= {:k nil} (swap! a assoc :k nil)))
      (is (= {:k nil :j 1} (swap! a assoc :j 1)))))
  (testing "reactive cell"
    (let [a (atom {})]
      (swap! a assoc :k nil)
      (is (= {:k nil} @a))))
  (testing "a nil in any argument position survives"
    (let [a (clojure.core/atom [])]
      (swap! a conj nil)
      (is (= [nil] @a))
      (swap! a (fn [v x y] (conj v [x y])) nil :b)
      (is (= [nil [nil :b]] @a)))))

(deftest watcher-fires-on-change
  (let [a (atom 0)
        fired (clojure.core/atom 0)
        watch (fn [_] (swap! fired inc))]
    ;; simulate a render: while *current-watcher* is bound, deref registers it.
    (binding [r/*current-watcher* watch]
      @a)                                   ; register
    (is (zero? @fired) "no fires before a change")
    (reset! a 1)
    (is (= 1 @fired) "the watcher fires once on change")
    (reset! a 1)
    (is (= 1 @fired) "no fire when value is unchanged")))

(deftest watcher-registered-once
  (let [a (atom 0)
        fired (clojure.core/atom 0)]
    (binding [r/*current-watcher* (fn [_] (swap! fired inc))]
      @a @a @a)                             ; three reads, one watcher
    (reset! a 1)
    (is (= 1 @fired) "repeated derefs register the watcher only once")))

(deftest cursor-reads-and-writes-the-source
  (let [a (atom {:name "jolt" :count 0})
        c (cursor a [:count])]
    (is (= 0 @c))
    (reset! c 7)
    (is (= 7 @c))
    (is (= 7 (:count @a)) "writing the cursor updates the source path")
    (swap! c inc)
    (is (= 8 (:count @a)))))

(deftest cursor-propagates-to-its-watchers
  (let [a (atom {:x 1})
        c (cursor a [:x])
        fired (clojure.core/atom 0)]
    (binding [r/*current-watcher* (fn [_] (swap! fired inc))]
      @c)                                   ; register a watcher on the cursor
    (reset! a {:x 2})                       ; mutate the source directly
    (is (= 1 @fired) "a cursor watcher fires when its source changes")))

(deftest reaction-derives-and-recomputes
  (let [a (atom 2)
        ;; a reaction runs its body, tracking every reactive it derefs.
        squared (reaction (* @a @a))]
    (is (= 4 @squared))
    (reset! a 5)
    (is (= 25 @squared) "the reaction recomputes when a dependency changes")
    (reset! a 5)
    (is (= 25 @squared) "no recompute when the dependency value is unchanged")))

(deftest reaction-notifies-its-own-watchers
  (let [a (atom 1)
        doubled (reaction (* 2 @a))
        fired (clojure.core/atom 0)]
    (binding [r/*current-watcher* (fn [_] (swap! fired inc))]
      @doubled)                             ; subscribe to the reaction
    (reset! a 10)
    (is (= 1 @fired) "a reaction subscriber fires when the reaction recomputes")))

(deftest cursor-and-reaction-compose
  (let [state (atom {:todos [{:done false} {:done true} {:done false}]})
        done-count (reaction (count (filter :done (:todos @state))))]
    (is (= 1 @done-count))
    (swap! state update-in [:todos 0 :done] not)   ; mark first todo done
    (is (= 2 @done-count) "writing the source recomputes the derived reaction")))

(deftest reaction-keeps-one-watch-per-dependency
  ;; recompute! must register the SAME watcher fn on every recompute, so a reaction
  ;; holds exactly one watch on each dependency. A fresh closure per recompute grows
  ;; the dep's watch set every update (1,2,4,8...) which, in a component, becomes a
  ;; runaway render storm after a handful of keystrokes.
  (let [a (atom 0)
        _  (reaction (inc @a))]
    (dotimes [_ 6] (swap! a inc))
    (is (= 1 (count (clojure.core/deref (:watches a))))
        "a reaction must keep exactly one watch on a dependency across recomputes")))
