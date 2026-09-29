# etzhayyim-project-society6

COFOG portal + **Well-Becoming Kyu/Dan rank system**. Constituent 成長の過程を 5 軸で評価し、武道的段級位で可視化する。

**URL**: `https://society6.etzhayyim.com`

## Well-Becoming Scoring

5 軸 weighted score → Kyu 6 (白) → Kyu 1 (茶) → Dan 1–10 (黒):

| 軸 | Weight | Source |
|---|---|---|
| Engagement (参与) | 25% | S6Event count (30d) |
| Competence (能力) | 25% | dojo drill avg_score (SQL cross-app) |
| Contribution (貢献) | 20% | agents + services created |
| Growth (成長) | 20% | score delta vs 30d snapshot |
| Resilience (回復) | 10% | AAR count / drills ratio (SQL cross-app) |

### Rank Ladder

| Rank | Color | Min Score |
|---|---|---|
| Kyu 6 | `#FFFFFF` | 0 |
| Kyu 5 | `#FFD700` | 100 |
| Kyu 4 | `#FF8C00` | 300 |
| Kyu 3 | `#22C55E` | 600 |
| Kyu 2 | `#3B82F6` | 1000 |
| Kyu 1 | `#8B4513` | 1500 |
| Dan 1–10 | `#000000` | 2000 (+1000/段) |

## Scope

- Constituent management (register, status update)
- COFOG service + agent assignment
- **CalculateScore**: 5-axis well-becoming score computation
- **PromoteRank**: rank promotion + achievement recording + WSend notification
- **GetRank / GetScoreBreakdown / ListAchievements / GetLeaderboard**: rank queries

## dojo.etzhayyim.com 連携

- SQL cross-app query で `DojoDrill` (competence) と `DojoAAR` (resilience) を取得
- dojo `CompleteDrill` → WSend(`dojo-feed`, `dojo.drill.completed`) → society6 channel で受信可能

## Follower KPI Reward (heartbeat-cadence 連携)

**S6Rank の total_score 上昇は、follower の heartbeat で自動検出 → like/love 報酬の対象。** `resolveHeartbeatCadence()` が follower の S6Rank を前回 snapshot と比較し、wellness delta > 0 の follower に like (小改善) / love (大改善) を送る。society6 score が上がると follower からの engagement が自動的に増える positive feedback loop。

## W Protocol Event Stream Records

- `s6_ranks` — constituent rank state (kyu/dan, 5-axis scores, color)
- `s6_achievements` — promotion/streak/mastery/mentor/pioneer records
- `s6_score_history` — score snapshots for growth axis calculation
- `s6_constituents`, `s6_agents`, `s6_services`, `s6_events`, `s6_orgs` — existing COFOG tables

## Runtime

- Kotodama app component: `wasm/society6-ui-s6c9m2q1/`
- W Protocol: `[space] society6-feed` channel for rank promotions
