# Solveur ILS + sac à dos — Hash Code 2017 « Streaming videos »

Chaîne complète : parser → glouton → descente par blocs → recherche locale itérée,
plus un solveur exact pour les instances à structure exploitable.

## Résultats

| Instance | Glouton | + Sac à dos | + ILS | **Retenu** | % borne sup* |
|---|---|---|---|---|---|
| me_at_the_zoo | 507 906 | 512 115 | 516 557 | **516 557** | 92,0 % |
| videos_worth_spreading | 608 303 | 610 417 | 611 600 | **611 600** | 74,8 % |
| trending_today | 500 000 | — | — | **500 000** 🏆 | **100 %** |
| kittens | 1 021 681 | 1 024 969 | 1 024 969 | **1 024 969** | 68,7 % |
| **TOTAL** | 2 637 890 | | | **2 653 126** | 78,7 % |

Mesuré avec 8 threads et 150 s par instance (la commande ci-dessous utilise 16 threads : avec
plus de cœurs, l'ILS coopératif a plus de kicks à fusionner). La colonne « Glouton » de
`trending_today` est le bin-packing exact : cette instance ne passe pas par le glouton.

\* borne obtenue en servant chaque requête à son meilleur cache connecté, capacité ignorée
(donc lâche : sur kittens seuls 60 % du volume vidéo tient dans les caches).

`trending_today` atteint **l'optimum prouvé**.

## Utilisation

Le solveur est branché dans `GoogleHash2017` via `solver.impl.ILSSolver` :

```bash
make compile
java -Xmx8g -Dils.seconds=150 -Dils.threads=16 -cp out GoogleHash2017 instances/kittens.in output/kittens.out
```

`-Dils.seconds` (défaut 60) est le budget par instance, `-Dils.threads` (défaut : cœurs − 2)
le nombre de threads de l'ILS. `make instances/kittens.in` marche aussi (budget par défaut).

## Architecture

| Fichier | Rôle |
|---|---|
| `src/solver/ils/Instance.java` | Parser sur `byte[]`, listes d'adjacence CSR + matrice de latence (`-1` = non connecté), requêtes triées par endpoint, CSR cache → endpoints |
| `src/solver/ils/Solution.java` | Contenu des caches en bitset plat, capacités, validateur, écriture soumission |
| `src/solver/ils/Scorer.java` | Scorer **indépendant** (recalcul intégral) + borne supérieure |
| `src/solver/ils/Book.java` | Le carnet `best1` / `best2` / `arg1` — évaluations en O(1) |
| `src/solver/ils/Greedy.java` | Glouton paresseux CELF, tas binaire sans autoboxing |
| `src/solver/ils/BlockKnapsack.java` | Descente par blocs, sous-problème résolu **exactement** par sac à dos (fixation par coûts réduits + DP sur le cœur) |
| `src/solver/ils/ILS.java` | Recherche locale itérée : kicks sur caches voisins avec annulation, fusion coopérative des threads (marche indépendante pour ≤ 32 caches) |
| `src/solver/ils/BinPacking.java` | Subset-sum exact pour les instances dégénérées |
| `src/solver/impl/ILSSolver.java` | Adaptateur `Solver` : `Problem` → `Instance`, pipeline + jalons de vérification, réécriture des caches dans `Problem` |

## Les idées qui portent le score

### 1. Le gain se mesure contre l'existant, jamais contre le data center

Le gain de placer `v` dans `c` vaut `Rn × max(0, best1(e,v) − L(e,c))`, où `best1` est la
meilleure latence **déjà disponible**. Un glouton qui compare à `L_D` croit qu'une 10ᵉ copie
d'une vidéo populaire rapporte encore autant que la 1ʳᵉ, et remplit les caches de doublons.

Mesure sur les instances — valeur d'une copie **redondante** par rapport à la première :

| Instance | 1ʳᵉ copie | Copie redondante | Ratio |
|---|---|---|---|
| me_at_the_zoo | 579,5 ms | 52,6 ms | 9,07 % |
| videos_worth_spreading | 877,5 ms | 32,3 ms | 3,68 % |
| trending_today | 500,0 ms | 0,0 ms | 0,00 % |
| kittens | 1 491,2 ms | 1,3 ms | **0,09 %** |

Sur kittens le problème n'est donc pas « choisir le meilleur cache » mais **couvrir** un
maximum de paires (endpoint, vidéo) au moins une fois.

Cette propriété — le gain d'une action ne peut que diminuer quand d'autres ont déjà été
faites — est la **sous-modularité**. Elle rend valide le tri paresseux du glouton (CELF) :
on ne réévalue que le sommet du tas, ce qui donne un résultat **identique** au glouton naïf
pour ~100× moins de calcul.

### 2. Les trois types d'endpoints, gérés sans cas particulier

Un endpoint n'est pas connecté à tous les caches. Le carnet `best1`/`best2` encode la
différence, en initialisant tout à `L_D` (le data center est le repli universel) :

| Type | Carnet | Comportement |
|---|---|---|
| **Orphelin** (0 cache) | `best1 = best2 = L_D` | Immobile, aucun gain possible ; compte au dénominateur |
| **Mono-cache** | `best2 = L_D` toujours | Fragile : retirer la vidéo le renvoie au data center |
| **Multi-cache** | `best2` = un autre cache proche | Robuste : retirer coûte `best2 − best1`, souvent ~1 ms |

Répartition réelle :

| Instance | Orphelins | Mono-cache | Multi-cache | K moyen |
|---|---|---|---|---|
| me_at_the_zoo | 0 | 1 (7,7 % req.) | 9 | 3,2 |
| videos_worth_spreading | **7** (7,0 % req.) | 11 (10,9 %) | 82 | 5,2 |
| trending_today | 0 | 0 | 100 | 100 |
| kittens | 0 | 0 | 1000 | 351,9 |

### 3. Décomposition exacte par cache (sac à dos)

**Si l'on fige le contenu de tous les caches sauf `c`, le contenu optimal de `c` est
exactement la solution d'un sac à dos 0/1.**

Le gain `g_c(v)` ne dépend d'aucune autre vidéo de `c` : une requête ne porte que sur une
seule vidéo, donc il n'y a **aucune interaction intra-cache** — les vidéos ne se disputent
que la place. Valeurs `g_c(v)`, poids `S_v`, capacité `X`.

On itère sur les caches en ordre aléatoire jusqu'au point fixe. Le contenu précédent étant
une solution réalisable du sac à dos, **le score est garanti non-décroissant** (vérifié à
l'exécution : le programme lève une exception sinon).

**Résolution exacte, mais sans la DP complète.** Une DP sur tous les objets coûte
`O(V·X)` par cache — c'était le poste dominant (48 kicks seulement en 20 s sur
`videos_worth_spreading`). Or l'optimum est presque entièrement fixé par la relaxation
linéaire : avec `r_b` le ratio de l'objet critique, `U` la borne fractionnaire et `LB` le
meilleur entre glouton et ancien contenu, le coût réduit `d_i = p_i − w_i·r_b` permet de
fixer un objet **dedans** si `d_i > U − LB`, **dehors** si `−d_i > U − LB` (sinon la borne
tombe sous `LB`). La DP ne porte plus que sur les quelques objets restants (le « cœur »),
sur la capacité restante. Le résultat est **identique** à la DP complète (vérifié cache
par cache sur me_at_the_zoo, videos_worth_spreading et un échantillon de kittens), sans la troncature à 1000 objets de l'ancienne version, et
**~40× plus rapide** sur `videos_worth_spreading`. Le gain marginal est aussi calculé en
ne parcourant que les requêtes des endpoints reliés au cache (requêtes triées par endpoint).

### 3 bis. Recherche locale itérée : voisinage, annulation, fusion

Après la descente, chaque cache est optimal isolément. `ILS.java` cherche des améliorations
qui demandent de modifier plusieurs caches à la fois :

- **Kick local** : on vide 1 à 2 caches *voisins* (qui partagent des endpoints — eux seuls
  interagissent) puis on ré-optimise 6 caches par sac à dos.
- **Annulation** : un journal du contenu des caches touchés permet de revenir en arrière
  sans reconstruire le carnet. Un kick moins bon n'est accepté qu'avec une probabilité
  Metropolis `exp(−Δ / (2·10⁻⁵ · score))`.
- **Fusion coopérative** : chaque thread journalise ses kicks améliorants ; un maître les
  rejoue et les **valide par le score** (annulés s'ils font baisser le total). Les
  améliorations de caches sans endpoint commun s'additionnent, au lieu de ne garder que la
  meilleure graine.
- **Petites instances (≤ 32 caches)** : tous les kicks interagissent, la fusion n'apporte
  rien ; on garde donc des marches indépendantes (acceptation large, retour au meilleur
  après 40 échecs). C'est ce qui atteint 516 557 sur `me_at_the_zoo`.

Mesuré sur `videos_worth_spreading` (8 threads, 20 à 30 s, depuis la même descente à 610 417) :
ancien ILS (marche aléatoire) `+130` ; kicks voisins avec annulation, meilleure des 8
graines `+450` ; avec fusion des threads `+800` ; avec Metropolis `+1 140` (moyenne de 2 essais,
le bruit d'un essai à l'autre est d'environ ±150).

### 4. `trending_today` n'est pas un problème de cache

Tous les endpoints ont `L_D = 600`, sont connectés aux 100 caches, tous à `L_c = 100`.
Peu importe *dans quel* cache une vidéo est placée — seul compte qu'elle soit cachée
quelque part. Et surtout :

```
Σ tailles vidéos = 5 000 000 Mo   =   C × X = 100 × 50 000 = 5 000 000 Mo
```

C'est un **bin-packing exact**. Best-Fit-Decreasing + réparations locales plafonne à
499 999 (une vidéo de 1000 Mo dehors, alors que les trous totalisent exactement 1000 Mo mais
éparpillés). La solution n'est pas une meilleure heuristique de rangement mais un changement
de problème : remplir chaque bin à **exactement** `X` par DP d'atteignabilité (subset-sum).
Tous les bins pleins ⟹ toutes les vidéos placées ⟹ **500 000, l'optimum**.

## Garde-fous

Trois vérifications tournent à chaque exécution et **font échouer le programme** si elles
sont violées :

1. **Capacité** — aucun cache ne dépasse `X` (une solution invalide vaut 0, pas « presque »)
2. **Cohérence** — le score incrémental du carnet doit égaler le scorer indépendant
3. **Monotonie** — la descente par blocs ne peut jamais faire baisser le score

## Pistes restantes

**kittens est un optimum local très solide** : aucun kick (jusqu'à 8 caches vidés et 24
ré-optimisés) ne trouve d'amélioration. Une borne de Lagrange indicative (une seule copie par
vidéo, capacités relaxées par des prix `λ_c`) converge vers ≈ 1 026 400, contre 1 024 969
obtenus : il reste **au plus ~0,14 %**. Les doublons ne pèsent que 2,3 % du volume et les
caches sont pleins à 123 Mo près. Cette borne n'a pas de sens sur `videos_worth_spreading`,
où les copies multiples valent cher.

Deux directions restent ouvertes, pour des gains attendus faibles :

1. **GRASP / amorce par les prix de Lagrange** — diversifier le *point de départ* plutôt que
   les perturbations ; aujourd'hui toutes les graines partent de la même solution descendue.
2. **Optimisation jointe de 2 caches** — la descente est optimale par cache isolé ; ce qui
   lui échappe demande de réorganiser deux caches simultanément.

Essayé sans gain : kicks à taille adaptative (le voisinage s'élargit quand la recherche
stagne) — équivalent à la taille fixe dans le bruit de mesure.
