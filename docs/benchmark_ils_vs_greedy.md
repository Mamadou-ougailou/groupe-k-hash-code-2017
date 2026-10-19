# Benchmark & Analyse Expérimentale : ILS vs Greedy Simple
**Projet Google Hash Code 2017 — "Streaming Videos"**

---

## 1. Tableau Comparatif & Benchmark

Le tableau ci-dessous compare les performances de l'approche **Greedy Simple** (glouton CELF par gain marginal) et du solveur complet **ILS (Iterated Local Search + Sac à dos exact + Bin-Packing)**.

| Instance | Glouton Simple | Solveur ILS | Gain Absolu | Gain Relatif (%) | Temps Glouton | Temps ILS (budget) | Borne Sup. Estimée | % Borne Sup (ILS) |
|---|---|---|---|---|---|---|---|---|
| **me_at_the_zoo** | 507 906 | **516 557** | +8 651 | +1,70 % | ~0,15 s | ~20 s (8 th.) | ~561 475 | **92,0 %** |
| **videos_worth_spreading** | 608 303 | **611 600** | +3 297 | +0,54 % | ~1,8 s | ~150 s (16 th.) | ~817 647 | **74,8 %** |
| **trending_today** | 499 999 | **500 000** | +1 | +0,0002 % | ~0,8 s | ~1,2 s | 500 000 | **100,0 %** 🏆 *(Optimum)* |
| **kittens** | 1 021 681 | **1 024 969** | +3 288 | +0,32 % | ~4,2 s | ~150 s (16 th.) | ~1 491 949 | **68,7 %** |
| **TOTAL** | **2 637 889** | **2 653 126** | **+15 237** | **+0,58 %** | **~7,0 s** | **Budget total** | **~3 371 071** | **78,7 %** |

> *Note sur `trending_today` : L'heuristique gloutonne / BFD plafonne à 499 999 (laissant une vidéo de 1000 Mo non placée). L'approche exacte du module ILS résout ce cas par partitionnement exact (subset-sum DP) et atteint 500 000, l'optimum mathématique prouvé.*

---

## 2. Experimental Analysis (Analyse Expérimentale)

### Protocole & Environnement de Test
- **Matériel de test** : CPU multi-cœurs (configurations 8 à 16 threads), allocation JVM `-Xmx8g`.
- **Paramètres d'exécution** :
  - `Greedy Simple` : Glouton paresseux mono-passe (arrêt immédiat à épuisement des gains positifs ou des capacités).
  - `ILS` : Phase gloutonne CELF → Descente par blocs (DP cœur knapsack) → Recherche locale itérée multi-thread coopérative (budget 60 s à 150 s par instance).
- **Métrique officielle de score** :
  $$\text{Score} = \left\lfloor \frac{1000 \times \sum_{r} R_n \cdot (L_{DC} - L_{\text{servi}})}{\sum_{r} R_n} \right\rfloor$$
- **Validation stricte (Garde-fous)** :
  1. Respect strict des capacités des caches ($\sum_{v \in c} S_v \le X$, sinon score = 0).
  2. Cohérence du scorer incrémental avec le `Scorer` arbitre indépendant.
  3. Monotonie de la descente par blocs (interdiction de régression du score).

---

## 3. Stats Between Versions (Évolution des Versions)

Le tableau suivant retrace le gain incrémental apporté par chaque palier algorithmique du projet :

```
[Glouton CELF] ───(+9 499 pts)───> [+ Sac à dos exact] ───(+5 738 pts)───> [+ ILS Coopératif]
   2 637 889                         2 647 388 (avec 500k)                2 653 126
```

| Palier / Version | Score Global | Δ Gain vs Version Précédente | Caractéristiques clés |
|---|---|---|---|
| **V1. Cache-First / Video-First** | < 2 100 000 | Référence initiale | Ordre statique figé au départ ; ignore la mise à jour des latences intermédiaires. |
| **V2. Greedy Simple (CELF)** | **2 637 889** | **+537 889 pts** | Gain calculé contre `best1` dynamique (sous-modularité exploitée par tas paresseux). |
| **V3. + Sac à Dos par Blocs** | **2 647 388** | **+9 499 pts** | Décomposition par cache isolé résolu à l'optimum exact via fixation par coûts réduits. |
| **V4. + ILS Parallèle & Bin-Packing** | **2 653 126** | **+5 738 pts** | Perturbation ("ruin & recreate") sur caches voisins, fusion de kicks, partitionnement exact sur `trending_today`. |

---

## 4. Justify Decisions (Justification des Choix Techniques)

1. **Mesure du gain contre l'existant (`best1`), jamais contre le Data Center** :
   - *Raison* : Un glouton comparant à $L_{DC}$ attribue la même valeur à une 10ᵉ copie d'une vidéo qu'à la 1ʳᵉ. Sur l'instance `kittens`, une copie redondante ne rapporte que **0,09 %** de la première. Mesurer contre $L_{DC}$ conduit à saturer les caches de doublons sans gain réel.
2. **Carnet de latences `best1`, `best2`, `arg1` (Structure O(1))** :
   - *Raison* : Pour retirer ou échanger une vidéo, il faut connaître la latence de repli. Si la vidéo est retirée du meilleur cache `arg1`, la latence devient instantanément `best2`. Cela évite un recalcul en $O(\text{connexions})$ à chaque décision.
3. **Décomposition exacte en Sac à Dos 0/1 par cache** :
   - *Raison* : En fixant le contenu de tous les caches sauf un cache $c$, les requêtes ne portent que sur une vidéo à la fois. Les vidéos ne se gênent donc pas entre elles dans $c$ ; elles ne se disputent que la capacité. Le problème par cache est donc un **Knapsack 0/1 pur**.
4. **Fixation par coûts réduits (Core Knapsack DP)** :
   - *Raison* : Une programmation dynamique complète sur $V \times X$ consommait trop de temps (~48 kicks en 20 s). En fixant les objets évidents par relaxation linéaire et coûts réduits, la DP ne s'exécute que sur un sous-ensemble critique (le cœur). Cela apporte une accélération de **~40×** sans aucune perte d'optimalité.
5. **Kicks ciblés sur caches voisins & Fusion coopérative** :
   - *Raison* : Deux caches qui ne partagent aucun endpoint sont indépendants. Perturber des caches voisins force une réallocation coordonnée là où les interactions sont maximales. La fusion multithread valide les améliorations par le score global.
6. **Solveur dédié Subset-Sum pour `trending_today`** :
   - *Raison* : Comme $\sum \text{tailles} = C \times X$ et que toutes les latences sont identiques, le problème est une partition exacte. Une DP d'atteignabilité remplit chaque bin à 100 %, atteignant l'optimum absolu garanti.

---

## 5. Strengths & Weaknesses (Forces et Faiblesses)

### Greedy Simple (CELF)
- **Forces** :
  - Vitesse d'exécution remarquable (< 5 secondes sur toutes les instances).
  - Simplicité conceptuelle et mémoire minimale.
  - Atteint immédiatement ~99,4 % du score final grâce à la sous-modularité.
- **Faiblesses** :
  - Décisions irréversibles : incapable de revenir sur un placement sous-optimal.
  - Ne réordonne pas les combinaisons de vidéos pour optimiser l'espace résiduel (effet de fragmentation du sac).

### Solveur ILS (+ Knapsack & DP)
- **Forces** :
  - Capacité d'évasion des optima locaux grâce au ruin & recreate ciblé.
  - Résolution exacte des sous-problèmes à chaque étape (garantie mathématique de non-régression).
  - Parallélisme efficace avec fusion de kicks indépendants.
  - Résolution prouvée à l'optimum (100 %) sur les topologies dégénérées.
- **Faiblesses** :
  - Consommation CPU et temps de calcul plus élevés (nécessite un budget de plusieurs dizaines de secondes).
  - Rendements marginaux décroissants sur les grandes instances très denses (ex: `kittens` où l'optimum local glouton est déjà extrêmement compact).

---

## 6. Analyses Approfondies

### Typologies d'Instances & Comportements
1. **Instances denses & compactes (`kittens`)** :
   - Les caches sont connectés à une multitude d'endpoints ($K_{\text{moyen}} = 351,9$).
   - Le volume vidéo total dépasse largement la capacité cumulée des caches (seuls ~60 % du volume vidéo rentrent).
   - L'espace résiduel moyen par cache n'est que de 123 Mo. L'optimum glouton est si robuste qu'aucun kick individuel ne permet de débloquer de saut majeur (borne lagrangienne estimée à $\le 0,14 \%$ de marge restante).
2. **Instances à topologie hétérogène (`me_at_the_zoo`, `videos_worth_spreading`)** :
   - Présence d'endpoints orphelins (7 sur `videos`), mono-caches et multi-caches.
   - Les interactions locales sont fortes : c'est sur ces instances que l'ILS tire son épingle du jeu (+8 651 pts sur `me_at_the_zoo`, +3 297 pts sur `videos_worth_spreading`).
3. **Instances symétriques / dégénérées (`trending_today`)** :
   - Problème déguisé de Bin-Packing : 100 % de couverture possible avec l'algorithme adéquat.

---

## 7. Next Steps (Prochaines Pistes d'Amélioration)

1. **Diversification GRASP / Relaxation Lagrangienne** :
   - Plutôt que de démarrer toutes les graines d'ILS depuis la même solution gloutonne, diversifier la solution initiale en pondérant les requêtes par des multiplicateurs de Lagrange $\lambda_c$.
2. **Optimisation conjointe Bi-Caches (2-Knapsack)** :
   - La descente par blocs optimise un cache à la fois. Ce qui échappe à la recherche locale nécessite de réallouer simultanément deux caches voisins partageant une intersection dense d'endpoints.
3. **Ajustement adaptatif du bruit et de la température Metropolis** :
   - Implémenter un recuit simulé (Simulated Annealing) dynamique basé sur le taux d'acceptation des kicks pour explorer plus agressivement les vallées profondes en fin de budget.
