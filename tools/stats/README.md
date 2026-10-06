# tools/stats — mesurer pour decider

Paquet de statistiques decouple : il ne depend que des formats `.in` et `.out`.
Aucun import vers `tools/dashboard` ni vers `src/`, donc il peut etre repris tel
quel dans un autre projet du meme sujet.

Il complete `tools/dashboard/instance_stats.py`, qui decrit l'instance (tailles,
popularite, Gini, degres, latences). Ce paquet repond a d'autres questions :
**reste-t-il de la marge**, et **ou un local search va-t-il s'enliser**.

## Utilisation

```bash
# une metrique isolee
python -m tools.stats.plateau instances/kittens.in

# tout, sur plusieurs instances, avec les solutions
python -m tools.stats.run_all instances/*.in --solution-dir output --csv stats.csv

# reglages conseilles
python -m tools.stats.advisor instances/*.in --solution-dir output --trace-dir traces --budget 240

# confronter les regles au tirage au sort
python -m tools.stats.tuner instances/kittens.in --seconds 15 --random 6
```

Chaque module de metrique s'execute seul et expose un `compute()` reutilisable.
La sortie est normalisee : `{"metric": ..., "instance": ..., "values": {...}}`.

## Les metriques

| Module | Mesure | Decision qu'elle informe |
|---|---|---|
| `ceiling` | Score maximal si chaque requete etait servie par son meilleur cache connecte, capacite ignoree | Reste-t-il de la marge sur cette instance. Aligne sur `Scorer.upperBound` en Java, valeurs identiques. |
| `plateau` | Ecart entre meilleur et deuxieme meilleur cache par endpoint, pondere par le volume | Taille du plateau, **avant** d'avoir lance un solveur. Ecart nul = retrait gratuit = hill-climbing bloque. |
| `overlap` | Jaccard entre caches sur leurs ensembles d'endpoints | Redondance. La moyenne seule ne distingue pas une topologie aleatoire d'une geographique : la queue de distribution le fait. |
| `granularity` | Videos par cache, pression de capacite, CV des tailles, correlation taille/popularite, duplications | Quels parametres valent la peine d'etre cherches. CV proche de 0 : trier par `gain/taille` revient a trier par `gain`. |
| `solution` | Cout de retrait par placement, saturation, efficacite par cache, replication | Diagnostic du local search. La part de retraits a cout nul est la taille **reelle** du plateau. |
| `advisor` | Classification en archetype, configuration conseillee, repartition du budget | Quels reglages passer au solveur pour cette instance. |
| `tuner` | Conseiller contre recherche aleatoire, a budget egal | La regle vaut-elle mieux que le hasard. |

## Ce que mesurent les quatre instances officielles

| Instance | plafond | vid/cache | plateau | ecart best2−best1 | recouv. p95 | paires disjointes | archetype |
|---|---|---|---|---|---|---|---|
| me_at_the_zoo | 561 356 | 4,0 | 0,00 | 61,3 ms | 0,63 | 0,31 | sac a dos serre |
| videos_worth_spreading | 817 516 | 33,3 | 0,02 | 98,3 ms | 0,14 | 0,72 | rigide fragmentee |
| trending_today | 500 000 | 100,0 | **1,00** | **0,0 ms** | **1,00** | **0,00** | degeneree |
| kittens | 1 492 627 | 12,0 | 0,37 | **1,3 ms** | 0,62 | 0,00 | plateau large |

`trending_today` est entierement degeneree : tous les caches sont
interchangeables, c'est du bin-packing et non du placement. `BinPacking.isDegenerate`
le detecte deja par une heuristique ; `plateau` le retrouve independamment.

Sur `kittens`, l'ecart moyen est de 1,3 ms : deplacer une video d'un cache a
l'autre ne change presque rien au score. C'est structurellement le terrain le
plus ingrat pour un local search.

## Honnetete methodologique

Les seuils de `advisor` sont des **hypotheses** calibrees sur quatre instances.
Quatre points ne permettent aucun apprentissage : ce sont des regles
interpretables, pas un modele. C'est pourquoi `tuner` existe et impose la
comparaison au tirage au sort a budget egal. Une regle qui n'arrive pas a battre
la mediane d'une recherche aleatoire ne doit pas etre presentee comme un apport.

Pour valider les regles sur plus que quatre points, `instance-studio/` genere des
instances dont on controle explicitement le plateau, le recouvrement et la
granularite.
