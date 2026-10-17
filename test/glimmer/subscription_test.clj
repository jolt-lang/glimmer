(ns glimmer.subscription-test
  "Component subscriptions are exactly the cells the latest render read, and they
  are detached the moment a subtree is unmounted, replaced or fails to mount."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [glimmer.backend :as b]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [glimmer.mock-backend :as mock]))

(use-fixtures :each
  (fn [test]
    (mock/install!)
    (reset! b/loop-running? false)
    (try (test) (finally (reset! b/loop-running? false)))))

(defn- label-of [win] (get-in @(first (mock/children win)) [:props :label]))

(deftest partial-mount-detaches-new-child-subscriptions
  (let [cell (r/atom 0) win (mock/widget :window {})
        bad (fn [] @cell (throw (ex-info "render failed" {})))]
    (is (thrown? Exception (ui/mount win :window [:vbox [bad]])))
    (is (empty? @(:watches cell)))
    (is (empty? (mock/children win)))))

(deftest partial-keyed-mount-detaches-new-child-subscriptions
  (let [cell (r/atom 0) win (mock/widget :window {})
        bad (fn [] @cell (throw (ex-info "render failed" {})))]
    (is (thrown? Exception (ui/mount win :window [:vbox [:label {:key "ok" :label "x"}] [bad {:key "bad"}]])))
    (is (empty? @(:watches cell)))
    (is (empty? (mock/children win)))))

(deftest conditional-subscriptions-and-nil-components-dispose-immediately
  (let [show (r/atom true) cell (r/atom "value") win (mock/widget :window {})
        root (ui/mount win :window [(fn [] (when @show [:label {:label @cell}]))])]
    (is (= 1 (count @(:watches cell))))
    (reset! show false)
    (is (empty? @(:watches cell)))
    (is (empty? (mock/children win)))
    (ui/unmount! root win :window)
    (is (empty? @(:watches show)))))

(deftest replacing-form-two-components-discards-state-and-subscriptions
  (let [cell (r/atom 0) which (r/atom true) mounts (atom [])
        a (fn [] (swap! mounts conj :a) (fn [] [:label {:label @cell}]))
        z (fn [] (swap! mounts conj :z) (fn [] [:label {:label "z"}]))
        win (mock/widget :window {})
        root (ui/mount win :window [(fn [] [(if @which a z)])])]
    (is (= [:a] @mounts))
    (reset! which false)
    (is (= [:a :z] @mounts))
    (is (empty? @(:watches cell)))
    (is (= "z" (label-of win)))
    (ui/unmount! root win :window)
    (is (empty? @(:watches which)))))

;; A different component fn at a slot reuses the expanded subtree in place: only
;; the previous Form-2 render fn is discarded. A nested component both fns share
;; therefore keeps its widget and its local state, while the slot's subscription
;; scope is refreshed to what the new fn reads. Full teardown would pass the
;; subscription check too, so the widget identity and the constructor count are
;; what pin the chosen behaviour.
(deftest a-different-component-fn-at-a-slot-reuses-its-expanded-subtree
  (let [which (r/atom true) only-a (r/atom 0) constructions (atom 0) nested-state (atom nil)
        win (mock/widget :window {})
        nested (fn [] (swap! constructions inc)
                 (let [n (r/atom 0)]
                   (reset! nested-state n)
                   (fn [] [:label {:label (str "n=" @n)}])))
        a (fn [] @only-a [:vbox [nested]])
        z (fn [] [:vbox [nested]])
        root (ui/mount win :window [(fn [] [(if @which a z)])])
        vbox-id (first (mock/ids win))]
    (reset! @nested-state 5)
    (is (= 1 @constructions))
    (is (= 1 (count @(:watches only-a))))
    (is (= "n=5" (label-of (first (mock/children win)))))
    (reset! which false)
    (is (= vbox-id (first (mock/ids win))) "the container widget is reused, not recreated")
    (is (= 1 @constructions) "the nested Form-2 constructor is not re-invoked")
    (is (= "n=5" (label-of (first (mock/children win)))) "so its local state survives")
    (is (empty? @(:watches only-a)) "a cell the new fn does not read is dropped")
    (ui/unmount! root win :window)
    (is (empty? @(:watches which)))))

(deftest replacing-a-form-two-component-with-a-form-one-drops-its-render-fn
  (let [which (r/atom true) tick (r/atom 0) win (mock/widget :window {})
        two (fn [] (fn [] [:label {:label "two"}]))
        one (fn [] [:label {:label (str "one " @tick)}])
        root (ui/mount win :window [(fn [] [(if @which two one)])])]
    (is (= "two" (label-of win)))
    (reset! which false)
    (is (= "one 0" (label-of win)))
    (reset! tick 1)
    (is (= "one 1" (label-of win)))
    (ui/unmount! root win :window)
    (is (empty? @(:watches tick)))))

(deftest queued-renders-are-fenced-by-disposal
  (let [queue (atom []) renders (atom 0) disposed (atom false)
        watcher (ui/make-rerender-watcher #(swap! renders inc) (atom true)
                                          #(swap! queue conj %) disposed)]
    (watcher nil)
    (reset! disposed true)
    ((first @queue))
    (is (zero? @renders))))

(deftest unmount-clears-the-root-when-native-removal-fails
  (let [cell (r/atom 0) win (mock/widget :window {})
        root (ui/mount win :window [(fn [] [:label {:label @cell}])])]
    (is (= 1 (count @(:watches cell))))
    (b/register! (assoc mock/backend :remove-child! (fn [& _] (throw (ex-info "native removal failed" {})))))
    (is (thrown? Exception (ui/unmount! root win :window)))
    (is (nil? @root))
    (is (empty? @(:watches cell)))))
