# Greedy Global — Approche gloutonne à gain marginal

## Pourquoi les deux autres approches scorent mal

Les approches cache-first et video-first partagent le même défaut fondamental :
elles fixent un **ordre statique** (des caches ou des vidéos) calculé une seule
fois au début, puis remplissent glouton dans cet ordre. Or chaque placement
modifie best_latency_so_far, ce qui change les gains de TOUS les placements
restants. L'ordre initial devient donc obsolète très vite.

## Idée clé : un seul niveau de glouton, gain marginal recalculé

Au lieu de boucler d'abord sur les caches ou les vidéos, on considère à chaque
pas **toutes** les paires (vidéo, cache) possibles et on choisit celle qui offre
le **meilleur gain marginal par unité de taille**. Après chaque placement on
met à jour best_latency_so_far et on recommence.

C'est coûteux si on recalcule tout à chaque pas. On utilise donc une
**évaluation paresseuse (lazy evaluation)** : on met les paires dans une file
de priorité (max-heap), et quand on dépile un élément on recalcule son gain.
Si le gain est toujours le meilleur, on l'accepte ; sinon on le réinsère.
Grâce aux rendements décroissants (sous-modularité du gain), la plupart des
éléments sont acceptés sans re-trier le reste.

---

## Structures de données

```
video_sizes[video_id]
endpoint_datacenter_latency[endpoint_id]
endpoint_to_caches[endpoint_id] -> [(cache_id, latence), ...]
video_to_endpoints_requesting[video_id] -> [(endpoint_id, nb_requetes), ...]
cache_capacity_used[cache_id]                   # initialisé à 0
best_latency_so_far[(endpoint_id, video_id)]    # initialisé à endpoint_datacenter_latency[endpoint_id]

# File de priorité max-heap sur le ratio gain/taille
heap = MAX_HEAP de (ratio, video, cache, timestamp)
iteration_globale = 0        # compteur de placements effectués
```

---

## Fonctions

```
FONCTION calculer_gain_marginal(video, cache):
    # Gain ACTUEL de placer cette vidéo dans ce cache,
    # en tenant compte de l'état courant de best_latency_so_far
    gain = 0
    POUR CHAQUE (endpoint, nb_requetes) DANS video_to_endpoints_requesting[video]:
        SI cache N'EST PAS DANS endpoint_to_caches[endpoint]:
            CONTINUER
        latence_cache = latence(endpoint, cache)
        g = max(0, best_latency_so_far[(endpoint, video)] - latence_cache)
        gain += nb_requetes * g
    RETOURNER gain
```

---

## Algorithme principal

```
ALGO PRINCIPAL:

    # 1. Initialisation
    POUR CHAQUE (endpoint, video) possible:
        best_latency_so_far[(endpoint, video)] = endpoint_datacenter_latency[endpoint]

    # 2. Construire le heap initial avec toutes les paires (vidéo, cache) possibles
    POUR CHAQUE video:
        POUR CHAQUE (endpoint, nb_requetes) DANS video_to_endpoints_requesting[video]:
            POUR CHAQUE (cache, _) DANS endpoint_to_caches[endpoint]:
                # Éviter les doublons (vidéo, cache) — utiliser un ensemble vu
                SI (video, cache) DÉJÀ VU:
                    CONTINUER
                marquer (video, cache) VU
                gain = calculer_gain_marginal(video, cache)
                SI gain > 0:
                    ratio = gain / video_sizes[video]
                    heap.insérer((ratio, video, cache, iteration_globale))

    # 3. Boucle gloutonne principale
    TANT QUE heap N'EST PAS VIDE:
        (ratio, video, cache, timestamp) = heap.extraire_max()

        # a. Vérifier la capacité
        SI cache_capacity_used[cache] + video_sizes[video] > X:
            CONTINUER  # plus de place, on abandonne cette paire

        # b. Vérifier si la vidéo est déjà dans ce cache
        SI video DANS cache_content[cache]:
            CONTINUER

        # c. Évaluation paresseuse : recalculer le gain si obsolète
        SI timestamp < iteration_globale:
            gain_actuel = calculer_gain_marginal(video, cache)
            SI gain_actuel <= 0:
                CONTINUER  # plus de gain, abandonner
            ratio_actuel = gain_actuel / video_sizes[video]
            # Réinsérer avec le gain mis à jour et le timestamp actuel
            heap.insérer((ratio_actuel, video, cache, iteration_globale))
            CONTINUER

        # d. Le gain est frais (timestamp == iteration_globale) → accepter le placement
        cache_content[cache].ajouter(video)
        cache_capacity_used[cache] += video_sizes[video]
        iteration_globale += 1

        # e. Mettre à jour best_latency_so_far
        POUR CHAQUE (endpoint, nb_requetes) DANS video_to_endpoints_requesting[video]:
            SI cache DANS endpoint_to_caches[endpoint]:
                latence_cache = latence(endpoint, cache)
                SI latence_cache < best_latency_so_far[(endpoint, video)]:
                    best_latency_so_far[(endpoint, video)] = latence_cache

    # 4. Générer la sortie
    POUR CHAQUE cache:
        AFFICHER cache_id, cache_content[cache]
```

---

## Complexité

- **Initialisation du heap** : O(V × C_moy × E_moy) — une fois.
- **Boucle principale** : chaque élément est inséré au plus ~P fois
  (P = nombre de placements effectués), mais en pratique la plupart sont
  acceptés dès la 2ème évaluation grâce à la sous-modularité.
- **Pire cas** : O(P × V × E) mais en pratique beaucoup plus rapide car
  la lazy evaluation court-circuite la majorité des recalculs.

## Pourquoi c'est meilleur

1. **Pas d'ordre statique** : chaque décision est prise avec l'état le plus
   récent du système.
2. **Gain marginal pur** : on mesure toujours le gain *incrémental* réel,
   pas un gain estimé une fois au début.
3. **Ratio gain/taille** : les petites vidéos à fort gain sont favorisées,
   ce qui maximise l'utilisation de la capacité limitée des caches.
4. **Lazy evaluation** : efficacité quasi-identique au tri initial, mais
   avec des décisions bien plus précises.
