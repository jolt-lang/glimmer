(ns glimmer.todo
  "A task board demo. Exercises a derived reaction (the counts), an expanding
  entry (:on-change / :on-activate, with a placeholder), checkbutton toggles, and
  list rendering inside a frame. Layout uses the universal GtkWidget props
  (margins, halign, hexpand, valign) and resolves GtkAlign nicks (:start, :center)
  at runtime via glimmer.genum — so glimmer carries no enum-constant table.

  Run: joltc -M:todo (the :todo task). Needs a display; closes the window to exit."
  (:require [glimmer.ratom :as r :refer [atom reaction]]
            [glimmer.core :as ui]))

;; A small "stat card": a big number over a muted label.
(defn- stat-card [n label]
  [:vbox {:spacing 0 :margin-start 14 :margin-end 14 :margin-top 10 :margin-bottom 10}
   [:label {:markup (str "<span size='xx-large' weight='bold'>" n "</span>") :halign :start}]
   [:label {:markup (str "<span color='#888888'>" label "</span>") :halign :start}]])

;; One task row: a checkbox (toggles :done by index) and the text, struck
;; through when done. `idx` stays stable because we only append/toggle, never
;; remove from the middle (positional reconciler — no keyed children yet).
(defn- task-row [idx state {:keys [text done]}]
  [:hbox {:spacing 8}
   [:checkbutton {:active done :valign :center
                  :on-toggled #(swap! state update-in [idx :done] not)}]
   [:label {:markup (if done (str "<s>" text "</s>") text)
            :halign :start :hexpand true :valign :center}]])

(defn todo-app []
  (let [state (atom [{:text "Try the glimmer counter demo" :done true}
                     {:text "Toggle a task below"          :done false}
                     {:text "Add one of your own"          :done false}])
        draft (atom "")
        total (reaction (count @state))
        done  (reaction (count (filter :done @state)))
        left  (reaction (count (remove :done @state)))
        add   (fn []
                (when (seq @draft)
                  (swap! state conj {:text @draft :done false})
                  (reset! draft "")))]
    (fn []
      [:vbox {:spacing 16 :margin 20}
       [:label {:markup "<span size='xx-large' weight='bold'>Tasks</span>" :halign :start}]

       [:hbox {:spacing 8}
        [stat-card @total "total"]
        [stat-card @done  "done"]
        [stat-card @left  "left"]]

       [:frame {:label (str @left " remaining") :vexpand true}
        [:vbox {:spacing 6 :margin 12}
         (if (empty? @state)
           [[:label {:markup "<span color='#888888'>Nothing here yet — add a task below.</span>"
                     :halign :start}]]
           (for [[idx t] (map-indexed vector @state)]
             [task-row idx state t]))]]

       [:hbox {:spacing 8}
        [:entry {:text @draft :placeholder "Add a task…"
                 :hexpand true :valign :center
                 :on-change   #(reset! draft %)
                 :on-activate add}]
        [:button {:label "Add" :on-click add :valign :center}]]])))

(defn -main [& _]
  (ui/run todo-app :title "glimmer · tasks" :width 480 :height 420 :app-id "glimmer.todo"))
