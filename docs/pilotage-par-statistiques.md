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

## Resultat 5 — la borne superieure est trop lache pour conclure

L'ecart au plafond vaut 31 % sur `kittens` et 25 % sur `videos_worth_spreading`,
mais ce plafond suppose chaque video presente dans chaque cache.

La relaxation fractionnaire par cache de `tools/stats/bound.py` ne le resserre
pas : elle compte l'economie d'une requete une fois par cache connecte, soit un
surcomptage de **x11,05** sur `kittens` et **x1,84** sur `me_at_the_zoo`,
l'ordre de grandeur du degre moyen.

On ignore donc si ces 31 % sont atteignables. C'est la seule question encore
ouverte, et elle demande une relaxation lineaire du probleme complet, avec
variables d'affectation.

## Conclusion

Le reglage par instance ne rapporte rien sur ces instances, et c'est mesure. Un
gain supposerait un changement d'algorithme, pas de parametres.

Avant d'investir dans un nouvel algorithme, il faut une borne superieure
exploitable : sans elle, impossible de savoir si l'on optimise vers une cible
atteignable ou si le solveur actuel est deja proche de l'optimum.

## Ce qui reste utile de ce travail

- `tools/stats/` mesure la structure d'une instance et diagnostique une solution
  (cout de retrait par placement, saturation, efficacite par cache)
- la trace JSON du solveur (`-Dils.trace`) rend chaque execution analysable
- `tools/stats/tuner.py` impose la comparaison au tirage au sort a budget egal,
  et peut servir a evaluer honnetement tout futur changement d'algorithme
- `instance-studio/` fabrique des instances dont on controle le plateau, le
  recouvrement et la granularite, pour tester une idee sur plus de quatre points
