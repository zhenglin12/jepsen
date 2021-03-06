(ns jepsen.dgraph.nemesis
  "Failure modes!"
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c]
                    [generator :as gen]
                    [util :as util]
                    [nemesis :as nemesis]]
            [jepsen.control.util :as cu]
            [jepsen.dgraph [support :as s]]))

(defn alpha-killer
  "Responds to :start by killing alpha on random nodes, and to :stop by
  resuming them."
  []
  (nemesis/node-start-stopper identity ;util/random-nonempty-subset
                              s/stop-alpha!
                              s/start-alpha!))

(defn alpha-fixer
  "Alpha likes to fall over if zero isn't around on startup, so we'll issue
  speculative restarts."
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)

    (invoke! [this test op]
      (assoc op :value
             (c/on-nodes test (util/random-nonempty-subset (:nodes test))
                         (fn [test node]
                           (if (cu/daemon-running? s/alpha-pidfile)
                             :already-running
                             (do (s/start-alpha! test node)
                                 :restarted))))))

    (teardown! [this test])))

(defn zero-killer
  "Responds to :start by killing zero on random nodes, and to :start by
  resuming them."
  []
  (nemesis/node-start-stopper util/random-nonempty-subset
                              s/stop-zero!
                              s/start-zero!))

(defn tablet-mover
  "Moves tablets around at random"
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)

    (invoke! [this test op]
      (let [state  (s/zero-state (rand-nth (:nodes test)))
            groups (->> state :groups keys)]
        (info :state (with-out-str (pprint state)))
        (->> state
             :groups
             vals
             (map :tablets)
             (mapcat vals)
             shuffle
             (keep (fn [tablet]
                     (let [pred   (:predicate tablet)
                           group  (:groupId tablet)
                           group' (rand-nth groups)]
                       (when-not (= group group')
                         ; Actually move tablet
                         (info "Moving" pred "from" group "to" group')
                         (s/move-tablet! (rand-nth (:nodes test)) pred group')
                         (info "Moved" pred "from" group "to" group')
                         ; Return predicate and new group
                         [pred [group group']]))))
             (into (sorted-map))
             (assoc op :value))))

    (teardown! [this test])))

(defn full-nemesis
  "Can kill and restart all processes and initiate network partitions."
  [opts]
  (nemesis/compose
    {{:fix-alpha        :fix}   (alpha-fixer)
     {:kill-alpha       :start
      :restart-alpha    :stop}  (alpha-killer)
     {:kill-zero        :start
      :restart-zero     :stop}  (zero-killer)
     {:start-partition  :start
      :stop-partition   :stop}  (nemesis/partition-random-halves)
     #{:move-tablet}            (tablet-mover)}))

(defn op
  "Construct a nemesis op"
  [f]
  {:type :info, :f f, :value nil})

(defn full-generator
  "Takes a nemesis specification map from the command line, and constructs a
  generator for the given types of nemesis operations, e.g. process kills and
  partitions."
  [opts]
  (->> [(when (:kill-alpha? opts)
          [(gen/seq (cycle [(op :kill-alpha)
                            (op :restart-alpha)]))])
        (when (:kill-zero? opts)
          [(gen/seq (cycle (map op [:kill-zero  :restart-zero])))])
        (when (:fix-alpha? opts)
          [(op :fix-alpha)])
        (when (:partition? opts)
          [(gen/seq (cycle (map op [:start-partition :stop-partition])))])
        (when (:move-tablet? opts)
          [(op :move-tablet)])]
       (apply concat)
       gen/mix
       (gen/stagger (:interval opts))))

(defn nemesis
  "Composite nemesis and generator"
  [opts]
  {:nemesis   (full-nemesis opts)
   :generator (full-generator opts)
   :final-generator (->> [(when (:partition?  opts) :stop-partition)
                          (when (:kill-zero?  opts) :restart-zero)
                          (when (:kill-alpha? opts) :restart-alpha)]
                         (remove nil?)
                         (map op)
                         gen/seq
                         (gen/delay 5))})
