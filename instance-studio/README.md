# Instance Studio

Générateur et validateur d'instances pour le problème **Hash Code 2017 — « Streaming videos »**,
avec une interface web et un partage par tunnel Cloudflare.

## Démarrage

```bash
./run.sh              # http://127.0.0.1:8000
./tunnel.sh           # URL publique https://<aléatoire>.trycloudflare.com
```

Dépendances : Python ≥ 3.10 et `numpy`. Rien d'autre — le serveur n'utilise que la
bibliothèque standard. `cloudflared` est nécessaire uniquement pour le tunnel.

Les instances générées sont écrites dans `data/instances/` : un fichier `.in` et un
fichier `.json` contenant les paramètres et les statistiques. Elles apparaissent
dans l'onglet **Instances** pour toute personne ayant l'URL.

## Paramètres et ce qu'ils contrôlent

### Dimensions
`V`, `E`, `C`, `X`, `R` et la graine. `R` est le **nombre de lignes de requêtes**, et
il est respecté exactement — ce n'est pas une quantité émergente.

### Tailles des vidéos
`uniforme`, `log-normale` ou `bimodale`. La bimodale (beaucoup de petites vidéos plus
quelques très grosses) est le régime où la fragmentation du sac à dos pèse le plus.
La statistique `video_size_cv` renvoyée indique si l'exposant sur la taille a un sens :
proche de 0, toutes les vidéos ont la même taille et trier par `gain/taille` revient
à trier par `gain`.

### Topologie
- **géographique** : endpoints et caches sont placés sur un anneau, chaque endpoint
  est relié à ses caches les plus proches. Les caches voisins partagent des endpoints,
  ce qui produit du recouvrement réaliste (`cache_overlap_mean`).
- **aléatoire uniforme** : connexions sans structure, recouvrement faible et homogène.

Le **degré** (nombre de caches par endpoint) est un paramètre à part entière :
constant, uniforme sur un intervalle, ou Poisson. Un degré de 0 est autorisé et
produit des endpoints que rien ne peut servir.

### Latences
`Étalement` contrôle l'écart entre le meilleur cache d'un endpoint et les suivants :

| Étalement | Effet |
|---|---|
| `0.0` | Tous les caches d'un endpoint ont la même latence. Retirer une vidéo d'un cache ne coûte rien dès qu'un autre cache la contient : **plateau maximal**, le hill-climbing cale. |
| `0.5` | Régime intermédiaire. |
| `1.0` | Écarts maximaux, solution rigide, la plupart des échanges sont rejetés. |

C'est le levier qui permet de fabriquer des instances faciles ou difficiles **pour un
local search**, à dimensions identiques.

### Requêtes
Popularité des vidéos uniforme ou Zipf, demande par endpoint uniforme ou Zipf,
corrélation taille/popularité, bornes sur le compte de chaque requête, et un
**taux de duplication** explicite : la part de lignes qui répètent un couple
(vidéo, endpoint) déjà présent. Les instances officielles en contiennent beaucoup
(60 % pour `videos_worth_spreading`), et c'est le motif qui fait tomber les parseurs
qui écrasent au lieu d'accumuler.

## Statistiques renvoyées

Calculées à la génération et stockées avec l'instance :

| Clé | Sens |
|---|---|
| `score_ceiling` | Score maximal atteignable si la capacité était infinie. Borne supérieure : sert à savoir s'il reste de la marge sur une instance. |
| `videos_per_cache` | `X / taille moyenne`. Mesure la granularité du sac à dos. |
| `capacity_pressure` | `C·X / volume des vidéos demandées`. Problème serré ou lâche. |
| `degree_mean`, `degree_std`, `endpoints_without_cache` | Structure de connexion. |
| `cache_overlap_mean` | Jaccard moyen entre caches sur leurs ensembles d'endpoints. Estimé sur 300 caches échantillonnés si `C > 300`. |
| `video_size_cv` | Coefficient de variation des tailles. |
| `duplicate_rate_actual` | Taux de duplication effectivement obtenu. |

## Reproductibilité

Chaque famille de paramètres tire sur un **flux aléatoire indépendant** dérivé de la
graine. Changer `R` ou l'étalement des latences laisse les tailles de vidéos
strictement identiques. On peut donc faire varier un facteur à la fois, ce qui est
indispensable pour comparer des configurations d'algorithme honnêtement.

À graine égale et paramètres égaux, la sortie est identique octet pour octet.

## Validateur

Onglet **Valider** : on envoie un `.in`, le serveur vérifie toutes les bornes de
l'énoncé et renvoie les erreurs localisées (50 au maximum) plus un résumé.

Il contrôle notamment `0 < Rn ≤ 10000`, que `instanceValidator.py` du générateur
d'origine ne vérifie jamais — un fichier avec `Rn = 12000` y passe pour valide.

## API

| Méthode | Route | Rôle |
|---|---|---|
| `GET` | `/api/instances` | Liste des instances avec paramètres et statistiques |
| `GET` | `/api/instances/{id}/download` | Téléchargement du `.in` |
| `POST` | `/api/generate` | Corps JSON = paramètres, renvoie la métadonnée |
| `POST` | `/api/validate` | Corps = contenu brut du fichier, renvoie `{valid, errors, summary}` |

```bash
curl -X POST http://127.0.0.1:8000/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"name":"test","V":3000,"E":120,"C":40,"X":9000,"R":30000,"topology":"clustered"}'
```

## Exposition publique

Le tunnel rend le serveur accessible à toute personne disposant de l'URL, **sans
authentification**. Les garde-fous en place :

- tous les paramètres sont bornés côté serveur, aux limites de l'énoncé ;
- deux générations simultanées au maximum (`429` au-delà) ;
- 200 instances et 3 Go de stockage au maximum (`507` au-delà) ;
- corps limité à 64 Ko pour la génération, 64 Mo pour la validation ;
- téléchargement par identifiant vérifié au format, jamais par chemin.

Le générateur est **déclaratif** : il n'accepte que des paramètres typés, jamais du
code. C'est délibéré — le générateur d'origine prend des fonctions Python en
paramètres, ce qui, exposé derrière un tunnel, serait une exécution de code à distance.

Coupe le tunnel quand tu n'en as plus besoin.

### Si le tunnel ne s'établit pas

`cloudflared` a besoin du **port sortant 7844**. Si le log boucle sur
`failed to dial a quic connection`, le réseau bloque l'UDP : relance en forçant
HTTP/2, qui passe en TCP.

```bash
PROTOCOL=http2 ./tunnel.sh 8000
```

Le tunnel est opérationnel quand le log affiche un encadré contenant l'URL
`https://<aléatoire>.trycloudflare.com` suivi de lignes `Registered tunnel connection`.
Une URL affichée sans ces lignes signifie que la connexion n'a pas abouti et que
l'adresse ne répondra pas.

## Performance

Instance à la taille maximale autorisée (`V=10000`, `E=1000`, `C=1000`, `R=1000000`,
13,4 Mo) : **0,8 s** de génération, 1,1 s de validation. Tout est vectorisé en numpy,
sans boucle Python par cellule.
