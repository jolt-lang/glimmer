(ns glimmer.lifecycle-test
  "The explicit root lifecycle seam between glimmer.core/run and a backend."
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
(defn- labels [win] (mapv #(get-in @% [:props :label]) (mock/children win)))

(defn- recording-backend
  "The mock backend with every native op, :dispose! included, noting its name in
  `calls` before it runs, so a test can show that a code path made none."
  [calls]
  (-> (reduce (fn [backend op]
                (let [f (op mock/backend)]
                  (assoc backend op (fn [& args] (swap! calls conj op) (apply f args)))))
              mock/backend
              [:create! :apply-props! :append-child! :remove-child! :replace-child!
               :reorder-child!])
      (assoc :dispose! (fn [_] (swap! calls conj :dispose!) nil))))

(deftest explicit-lifecycle-follows-reload-without-a-mount-return-value
  (let [cell (r/atom 0) win (mock/widget :window {})]
    (b/register!
     (assoc mock/backend :run-lifecycle
            (fn [_ {:keys [mount! unmount!]}]
              (is (nil? (mount! win :window)))
              (dotimes [_ 8]
                (ui/reload!)
                (is (= 1 (count @(:watches cell))))
                (is (= 1 (count (mock/children win)))))
              (unmount!)
              (unmount!)
              (is (empty? @(:watches cell)))
              (is (empty? (mock/children win))))))
    (ui/run (fn [] [:label {:label @cell}]))
    (reset! cell 1)
    (ui/reload!)
    (is (empty? (mock/children win)))))

(deftest legacy-run-backends-detach-subscriptions-when-the-loop-exits
  (let [cell (r/atom 0) win (mock/widget :window {})]
    (b/register! (assoc mock/backend :run (fn [_ mount-root!] (mount-root! win :window) nil)))
    (ui/run (fn [] [:label {:label @cell}]))
    ;; A legacy backend owns its container, so the widget stays; the tree does not.
    (is (= 1 (count (mock/children win))))
    (is (empty? @(:watches cell)))
    (reset! cell 1)
    (is (= 0 (label-of win)))))

(deftest a-legacy-loop-that-throws-still-releases-its-root
  (let [cell (r/atom 0) win (mock/widget :window {}) next-win (mock/widget :window {})
        crash (ex-info "loop crashed" {})]
    (b/register! (assoc mock/backend :run
                        (fn [_ mount-root!] (mount-root! win :window) (throw crash))))
    (is (identical? crash (try (ui/run (fn [] [:label {:label @cell}])) nil
                               (catch Exception e e))))
    ;; run's finalizer detached the tree although the loop never returned normally
    (is (empty? @(:watches cell)))
    (reset! cell 1)
    (is (= [0] (labels win)))
    ;; and released live-root: nothing is left to reload, and the next run is an ordinary one
    (is (re-find #"no running app" (with-out-str (ui/reload!))))
    (b/register! (assoc mock/backend :run
                        (fn [_ mount-root!]
                          (mount-root! next-win :window)
                          (ui/reload! (fn [] [:label {:label "reloaded"}]))
                          (is (= ["reloaded"] (labels next-win)))
                          nil)))
    (ui/run (fn [] [:label {:label "next"}]))))

(deftest queued-reloads-belong-to-their-run
  (let [queue (atom []) win (mock/widget :window {}) phase (atom 0)]
    (b/register!
     (assoc mock/backend :schedule #(swap! queue conj %)
            :run-lifecycle
            (fn [_ {:keys [mount! unmount!]}]
              (reset! b/loop-running? true)
              (mount! win :window)
              (if (zero? @phase)
                (do (ui/reload! (fn [] [:label {:label "stale"}])) (swap! phase inc))
                (do ((first @queue))
                    (is (= "current" (label-of win)))))
              (unmount!))))
    (ui/run (fn [] [:label {:label "first"}]))
    (ui/run (fn [] [:label {:label "current"}]))))

(deftest a-later-run-takes-over-the-live-root
  (let [outer (mock/widget :window {}) inner (mock/widget :window {}) phase (atom 0)]
    (b/register!
     (assoc mock/backend :run-lifecycle
            (fn [_ {:keys [mount! unmount!]}]
              (if (zero? @phase)
                (do (swap! phase inc)
                    (mount! outer :window)
                    ;; a second run while this one is live replaces it quietly
                    (ui/run (fn [] [:label {:label "inner"}]))
                    (is (= "outer" (label-of outer)))
                    (unmount!))
                (do (mount! inner :window)
                    (ui/reload! (fn [] [:label {:label "inner reloaded"}]))
                    (is (= "inner reloaded" (label-of inner)))
                    (unmount!))))))
    (ui/run (fn [] [:label {:label "outer"}]))
    (is (empty? (mock/children outer)))
    (is (empty? (mock/children inner)))))

(deftest a-run-rejected-before-mounting-leaves-the-live-root-alone
  (let [win (mock/widget :window {}) phase (atom 0)]
    (b/register!
     (assoc mock/backend :run-lifecycle
            (fn [_ {:keys [mount! unmount!]}]
              (if (zero? @phase)
                (do (swap! phase inc)
                    (mount! win :window)
                    ;; like Qt refusing a second session while one is active
                    (is (thrown? Exception (ui/run (fn [] [:label {:label "second"}]))))
                    (ui/reload! (fn [] [:label {:label "reloaded"}]))
                    (is (= ["reloaded"] (labels win)))
                    (unmount!))
                (throw (ex-info "a session is already running" {}))))))
    (ui/run (fn [] [:label {:label "first"}]))
    (is (empty? (mock/children win)))))

(deftest a-reload-whose-root-throws-keeps-the-window-for-the-next-reload
  (let [win (mock/widget :window {})]
    (b/register!
     (assoc mock/backend :run-lifecycle
            (fn [_ {:keys [mount! unmount!]}]
              (mount! win :window)
              (is (thrown? Exception (ui/reload! (fn [] (throw (ex-info "broken root" {}))))))
              (is (empty? (mock/children win)))
              ;; the broken fn was never adopted, so a bare reload retries the previous root
              (ui/reload!)
              (is (= ["first"] (labels win)))
              (ui/reload! (fn [] [:label {:label "corrected"}]))
              (is (= ["corrected"] (labels win)))
              (unmount!)
              (is (empty? (mock/children win))))))
    (ui/run (fn [] [:label {:label "first"}]))))

(deftest unmounting-after-a-failed-reload-releases-the-run
  (let [win (mock/widget :window {})]
    (b/register!
     (assoc mock/backend :run-lifecycle
            (fn [_ {:keys [mount! unmount!]}]
              (mount! win :window)
              (is (thrown? Exception (ui/reload! (fn [] (throw (ex-info "broken root" {}))))))
              (unmount!)
              (ui/reload! (fn [] [:label {:label "after unmount"}]))
              (is (empty? (mock/children win))))))
    (ui/run (fn [] [:label {:label "first"}]))
    (is (empty? (mock/children win)))))

(deftest abandoning-a-run-detaches-it-without-native-calls
  (let [cell (r/atom 0) win (mock/widget :window {}) calls (atom []) queue (atom [])]
    (b/register!
     (assoc (recording-backend calls)
            :schedule #(swap! queue conj %)
            :run-lifecycle
            (fn [_ {:keys [mount! abandon!]}]
              (mount! win :window)
              (ui/reload!)
              (is (= [:create! :append-child! :remove-child! :create! :append-child!] @calls))
              (is (= 1 (count @(:watches cell))))
              ;; with the loop running, a change queues the re-render instead of drawing
              (reset! b/loop-running? true)
              (reset! cell 1)
              (is (= 1 (count @queue)))
              (reset! calls [])
              ;; the native side is already gone: detach, but touch nothing
              (abandon!)
              (is (empty? @(:watches cell)))
              (is (= [] @calls))
              ;; the queued re-render finds its component disposed and draws nothing
              ((first @queue))
              (is (= [] @calls))
              (is (= [0] (labels win))))))
    (ui/run (fn [] [:label {:label @cell}]))
    ;; run's own finalizer had nothing left to release, natively or otherwise
    (is (= [] @calls))
    (reset! cell 2)
    (is (= [0] (labels win)))))

(deftest unmount-abandon-and-reload-are-no-ops-once-a-run-is-abandoned
  (let [cell (r/atom 0) win (mock/widget :window {}) calls (atom [])]
    (b/register!
     (assoc (recording-backend calls) :run-lifecycle
            (fn [_ {:keys [mount! unmount! abandon!]}]
              (mount! win :window)
              (reset! calls [])
              (abandon!)
              (is (empty? @(:watches cell)))
              (abandon!)
              (unmount!)
              ;; the abandoned run is no reload target: its window may already be gone
              (ui/reload! (fn [] [:label {:label "into a destroyed window"}]))
              (is (= [] @calls))
              (is (= [0] (labels win))))))
    (ui/run (fn [] [:label {:label @cell}]))
    (is (= [] @calls))
    (is (= [0] (labels win)))
    (is (re-find #"no running app" (with-out-str (ui/reload!))))))

(deftest abandon-detaches-only-the-selected-independent-root
  (let [cell (r/atom 0) a (mock/widget :window {}) b (mock/widget :window {})
        root (fn [] [:label {:label @cell}])
        root-a (ui/mount a :window [root]) root-b (ui/mount b :window [root])]
    (is (= 2 (count @(:watches cell))))
    (ui/abandon! root-a)
    (ui/abandon! root-a)
    (is (= 1 (count @(:watches cell))))
    (reset! cell 1)
    (is (= 0 (label-of a)))
    (is (= 1 (label-of b)))
    (ui/unmount! root-b b :window)
    (is (empty? @(:watches cell)))))

(deftest failed-attachment-disposes-the-new-widget
  (let [win (mock/widget :window {}) disposed (atom [])]
    (b/register! (assoc mock/backend
                        :append-child! (fn [& _] (throw (ex-info "attach failed" {})))
                        :dispose! (fn [w] (swap! disposed conj (:tag @w)))))
    (is (thrown? Exception (ui/mount win :window [:label {:label "x"}])))
    (is (= [:label] @disposed))
    (is (empty? (mock/children win)))))
