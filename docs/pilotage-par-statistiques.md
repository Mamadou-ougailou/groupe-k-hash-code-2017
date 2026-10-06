# Pilotage par statistiques — protocole et resultats

Objectif de depart : choisir les reglages du solveur **par instance**, a partir
de statistiques calculees sur le fichier `.in` et sur les sorties du glouton.

Le protocole a refute cette hypothese. Ce document consigne les mesures, parce
qu'un resultat negatif mesure vaut mieux qu'un reglage flatteur non verifie.

## Protocole

Budget en secondes **identique** pour toutes les executions, graine fixee. Pour
chaque instance : la configuration par defaut, la configuration conseillee par
les regles d'archetype, et six configurations tirees au sort sur sept
parametres (`descentShare`, `sweeps`, `kick`, `local`, `temp`, `noise`,
`repair`).

Le conseiller consomme **une** execution, la recherche aleatoire en consomme
six. Une regle qui n'egale pas le meilleur tirage n'apporte rien.

Reproduction :

```bash
python -m tools.stats.tuner instances/kittens.in instances/videos_worth_spreading.in \
    instances/trending_today.in --seconds 15 --random 6 --seed 1
```

## Resultat 1 — les regles d'archetype sont refutees

| Instance | defaut | conseiller | alea max | alea med | verdict |
|---|---|---|---|---|---|
| kittens | 1 024 970 | 1 024 847 | 1 024 991 | 1 024 974 | sous la mediane |
| videos_worth_spreading | 613 353 | 612 745 | 613 307 | 612 857 | sous la mediane |
| trending_today | 500 000 | 500 000 | 500 000 | 500 000 | indecidable |

Somme : defaut 2 138 323, conseiller 2 137 592, soit **−731**.

Les seuils de `tools/stats/advisor.py` sont conserves tels quels, avec ce
resultat documente. Les recalibrer sur ces memes quatre instances reviendrait a
sur-ajuster sur les donnees qui ont servi a les invalider.

## Resultat 2 — la surface de reglage est plate

Dispersion des scores sur les huit configurations testees :

| Instance | min | max | ecart |
|---|---|---|---|
| kittens | 1 024 576 | 1 024 991 | **0,040 %** |
| videos_worth_spreading | 612 098 | 613 353 | **0,205 %** |
| trending_today | 500 000 | 500 000 | **0,000 %** |

Les parametres ne pilotent rien. C'est la raison de fond pour laquelle aucune
regle de choix ne pouvait fonctionner : il n'y a pas de choix a faire.

## Resultat 3 — le temps n'est pas davantage un levier

`kittens`, configuration par defaut :

| Budget | Score | Kicks | Ameliorations |
|---|---|---|---|
| 15 s | 1 025 068 | 3 324 | 151 |
| 60 s | 1 025 254 | 20 085 | 262 |
| 150 s | 1 025 324 | 39 978 | 337 |

Dix fois plus de temps rapporte **+0,025 %**. Le score de la descente par blocs
n'evolue plus au-dela de 60 s (1 024 969 a 60 s comme a 150 s) : elle a
converge.

## Resultat 4 — le glouton fait presque tout

Decomposition par phase sur `kittens` a 15 s :

```
glouton CELF        1 021 681      99,7 % du resultat final
+ descente exacte   1 024 909      + 0,32 %
+ ILS               1 025 068      + 0,016 %
```

## Resultat 5 — le solveur est quasi optimal

Le plafond de `ceiling` suppose chaque video presente dans chaque cache : il
ignore la capacite et surestime enormement. La relaxation fractionnaire par
cache de `bound.py` ne le resserre pas non plus, car elle compte l'economie
d'une requete une fois par cache connecte (surcomptage **x11,05** sur `kittens`,
**x1,84** sur `me_at_the_zoo`).

La relaxation lineaire du probleme complet (`tools/stats/lp_bound.py`) tranche.
Elle impose qu'une requete ne soit servie qu'une fois, donc elle ne surcompte
pas, et son optimum fractionnaire majore l'optimum entier.

| Instance | borne lache | cache unique | **borne LP** | score | **part de la meilleure borne** |
|---|---|---|---|---|---|
| me_at_the_zoo | 561 356 | 561 356 | **524 397** | 516 557 | **98,50 %** |
| videos_worth_spreading | 817 516 | 791 573 | **620 448** | 613 353 | **98,86 %** |
| trending_today | 500 000 | 500 000 | — | 500 000 | **optimum prouve** |
| kittens | 1 492 627 | **1 199 614** | hors de portee | 1 025 324 | **85,47 %** |

Sur `trending_today`, le score egale la borne superieure : comme l'optimum est
encadre par les deux, il vaut exactement 500 000. L'instance est resolue.

Les ecarts annonces plus haut (31 % et 25 %) etaient donc des artefacts : les
vrais ecarts valent **1,5 %** et **1,14 %**.

### Le cas de kittens

La relaxation lineaire y est hors de portee : chaque endpoint est relie a
**352 caches en moyenne sur 500**, soit 74,6 millions de variables. C'est aussi
ce qui explique son ecart best2-best1 de 1,3 ms — avec autant de caches
connectes, beaucoup ont des latences presque egales.

On ne dispose donc que de la borne "cache unique", bien plus grossiere. Pour
calibrer sa qualite : sur `videos_worth_spreading`, elle donne 791 573 la ou la
LP donne 620 448, soit 28 % au-dessus. Si l'ecart est du meme ordre sur
`kittens`, le solveur y est en realite bien plus proche que les 85,47 % affiches.

Ce qui est certain : l'optimum de `kittens` est compris entre 1 025 324
(atteint) et 1 199 614 (borne valide). Le solveur est donc au pire a 14,5 % de
l'optimum, et probablement bien moins.

## Conclusion

Le reglage par instance ne rapporte rien, et c'est mesure. Mais la raison de
fond est plus forte que prevu : **le solveur est deja a environ 98,5 % d'une
borne superieure valide**. Il n'est pas coince dans un mauvais optimum local, il
est proche de l'optimum.

C'est l'explication unifiee de tous les resultats precedents. Si huit
trajectoires de recherche tres differentes convergent a 0,04 % les unes des
autres, ce n'est pas parce qu'elles echouent de la meme facon, c'est parce
qu'elles trouvent toutes a peu pres la meme chose : le bon resultat.

Ajouter une couche sur le glouton, le sac a dos ou l'ILS ne peut donc rien
rapporter. Le gain theorique maximum restant est de l'ordre de 1 %, et une
partie de ce 1 % est l'ecart d'integralite de la relaxation, donc inatteignable.

## Ce qui reste utile de ce travail

- `tools/stats/` mesure la structure d'une instance et diagnostique une solution
  (cout de retrait par placement, saturation, efficacite par cache)
- la trace JSON du solveur (`-Dils.trace`) rend chaque execution analysable
- `tools/stats/tuner.py` impose la comparaison au tirage au sort a budget egal,
  et peut servir a evaluer honnetement tout futur changement d'algorithme
- `instance-studio/` fabrique des instances dont on controle le plateau, le
  recouvrement et la granularite, pour tester une idee sur plus de quatre points
