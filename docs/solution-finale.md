# Solution finale — configuration de competition

Contrainte : le juge execute l'algorithme **en direct**, sur des instances
**inconnues**, et il doit terminer en **quelques minutes**. Relancer plusieurs
fois est autorise.

## La commande

```bash
./solve.sh <instance.in> 60 final
```

Budget de 60 secondes, parametres par defaut, 8 Go de tas. Le script compile si
necessaire, fait juger la solution par le juge officiel, et **ne remplace le
fichier que si le score s'ameliore**.

Relancer est toujours gagnant : la variance entre executions vaut quelques
centaines de points sur les grosses instances (`kittens` a donne 1 024 970,
1 025 131 et 1 025 324 selon les runs).

## Scores du juge officiel C++

Budget de 60 s par instance, 184 s au total.

| Instance | Score | Temps |
|---|---|---|
| me_at_the_zoo | 516 557 | 60 s |
| videos_worth_spreading | 614 484 | 60 s |
| trending_today | 500 000 | **0,0 s** |
| kittens | 1 025 131 | 61 s |
| **TOTAL** | **2 656 172** | **184 s** |

`trending_today` est resolu instantanement et **exactement** par le chemin
bin-packing : son score egale la borne superieure, donc c'est l'optimum.

## Robustesse sur instances inconnues

Six structures volontairement eloignees des quatre officielles, generees par
`instance-studio/`, budget 60 s :

| Instance | particularite | score | borne valide | part | temps |
|---|---|---|---|---|---|
| max_taille | **R = 1 000 000**, V=10000, E=1000, C=1000 | 1 073 147 | 1 073 147 | **100 %** | 61 s |
| capacite_large | pression 302, tout tient | 1 080 532 | 1 080 532 | **100 %** | 60 s |
| tres_sparse | degre 1,5, 99 % de paires disjointes | 466 560 | 559 110 | 83,4 % | 60 s |
| plateau_total | 100 % des requetes sur plateau | 826 420 | 1 106 618 | 74,7 % | 61 s |
| capacite_serree | pression 0,0125, 2,5 videos/cache | 35 453 | 52 146 | 68,0 % | 61 s |
| tailles_bimodal | CV 1,02 | 457 340 | 995 931 | 45,9 % | 60 s |

**Six sur six valides, aucun crash, aucun depassement de budget**, y compris sur
un million de requetes. Le temps de lecture du fichier ne fait pas deborder le
budget : 61 s de temps mural pour 60 s demandees.

Les parts les plus basses sont calculees contre la borne "cache unique", qui est
grossiere : sur `videos_worth_spreading` elle surestime la relaxation lineaire
de 28 %. Elles ne signifient donc pas forcement une faiblesse.

## Ce qu'il ne faut PAS faire

Trois pistes ont ete testees et mesurees comme **inutiles** :

- **Regler les parametres par instance.** Huit configurations tres differentes
  ne deplacent le score que de 0,040 % sur `kittens`. Et sur des instances
  inconnues, des parametres cales sur les quatre officielles ne peuvent que
  nuire.
- **Allonger beaucoup le budget.** Multiplier le temps par dix rapporte
  +0,025 %. Soixante secondes capturent l'essentiel.
- **Ajouter une couche de recherche locale.** Sur les deux instances ou la
  relaxation lineaire est calculable, le solveur est deja a 98,5 % et 98,9 %
  d'une borne valide.

## Ce qui rapporte encore

1. **Relancer** et garder le meilleur — quelques centaines de points, gratuits.
2. **Ne jamais recalculer `trending_today`** : il est prouve optimal.
3. Si une instance inconnue ressemble a `tailles_bimodal` (coefficient de
   variation des tailles proche de 1), c'est le profil ou le solveur est le plus
   loin de sa borne. A surveiller avec
   `python -m tools.stats.granularity <instance>.in`.

## Diagnostic rapide d'une instance inconnue

```bash
python -m tools.stats.run_all <instance>.in     # structure, plafond, plateau
python -m tools.stats.bound <instance>.in       # borne valide tenant compte de la capacite
```

Si `part_requetes_sur_plateau` vaut 1 et `ecart_best2_best1_moyen` vaut 0,
l'instance est degeneree et sera resolue instantanement.
