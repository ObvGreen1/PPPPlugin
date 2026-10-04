# PPPPlugin (TrueSkill 2 BedWars1058 Rating System)

> [!WARNING]
> **END OF LIFE / UNSUPPORTED PROJECT**
> This project is **no longer supported** and will **not receive any updates, new features, or bug fixes**. It is provided as-is for archival and reference purposes.

---

## Overview

**PPPPlugin** is a Minecraft Spigot 1.8.8+ plugin designed for **BedWars1058** (specifically tailored for Ranked BedWars / RBW arenas). It implements a **TrueSkill 2** Bayesian rating system to calculate skill ratings ($\mu$ and $\sigma$), rank tiers, leaderboard positions, and inactivity decay.

---

## Features

- **TrueSkill 2 Rating Engine**:
  - Calculates dynamic skill rating based on team outcomes, individual performance, mu ($\mu$), and uncertainty ($\sigma$).
  - Computes conservative display rating: $R = \mu - 3\sigma \times \text{display-scale}$.
- **Activity & Progression Tracking**:
  - Configurable rolling time window (default: 30 days) to track match activity.
  - Requirement thresholds for games played and Elo floor before achieving rank tiers.
- **Rank Tiers**:
  - Dynamic ranking tiers (e.g. `Premium`, `Pugs`, `Pups`) evaluated live from ratings, activity, and percentiles.
  - Live progress reporting showing completion bars and missing requirements.
- **Inactivity Decay**:
  - Configurable rating decay for players inactive beyond a threshold of days.
- **PlaceholderAPI Expansion**:
  - Exposes placeholders for scoreboards, chat formatters, and tablists.
- **SQLite Storage**:
  - Fast, self-contained local SQLite database (`points.db`).

---

## Commands & Permissions

### Commands

| Command | Aliases | Description | Permission |
|---|---|---|---|
| `/ppppoints [player]` | `/pts`, `/ppp` | View own or another player's rating, rank progress, and activity. | `ppp.points` (self)<br>`ppp.points.others` (others) |
| `/ppppoints top` | `/pts top`, `/ppp top` | Display the TrueSkill leaderboard. | `ppp.points` |
| `/pppreload` | — | Reload `config.yml`, `messages.yml`, and reconnect the database. | `ppp.admin` |

### Permissions

- `ppp.points`: Allows viewing own rating and leaderboard (default: `true`).
- `ppp.points.others`: Allows viewing other players' ratings (default: `true`).
- `ppp.admin`: Administrative access for reloading the plugin (default: `op`).

---

## Placeholders (PlaceholderAPI)

| Placeholder | Description |
|---|---|
| `%ppp_current_rank%` | Current rank tier name (e.g. `Premium`, `Pugs`, `Pups`, or `None`). |
| `%ppp_points%` / `%ppp_rating%` | Integer TrueSkill display rating points. |
| `%ppp_recent_games%` | Number of games played within the rolling window. |
| `%ppp_required_games%` | Required games count for the target rank tier. |
| `%ppp_elo%` | Player Elo rating. |
| `%ppp_progress%` | Rank progress percentage towards the next rank tier. |
| `%ppp_next_rank%` | Name of the next rank tier in progression. |

---

## Configuration

### `config.yml`

```yaml
# Points Plugin Configuration

# Arena group that TrueSkill 2 tracking will apply to (case-sensitive)
arena-group: "RBW"

# Storage configuration
storage:
  file: "points.db"

# TrueSkill 2 System Settings
trueskill:
  default-mu: 25.0
  default-sigma: 8.333333333333334
  # Rating multiplier to turn conservative rating into integer display points (R = mu - 3 * sigma)
  display-scale: 40.0

# Rating Decay System
decay:
  enabled: true
  # How many days of inactivity before they start losing points
  days-inactive: 7
  # How many points they lose per day after passing the inactive threshold
  points-per-day: 10
  # The lowest point value decay can drop them to
  minimum-points: 0

# Rank System
ranks:
  # Rolling window, in days, used for the "games played" requirement
  window-days: 30
  # Seconds to wait after a ranked game ends before the progress report is sent
  progress-delay-seconds: 5
  premium:
    min-games: 60
    min-elo: 1600
  pugs:
    min-games: 45
    min-elo: 1300
  pups:
    min-games: 25
    min-elo: 1000

# Debug logging
debug: false
```

### `messages.yml`

All user-facing messages, command replies, formatting prefixes, and progress bars are customizable in `messages.yml`.

---

## Requirements & Building

### Requirements
- **Java 21+**
- **Maven 3.8+**
- **Spigot 1.8.8** server environment (or compatible fork)
- **BedWars1058** (v25.2+)
- **PlaceholderAPI** (v2.11.x, optional for placeholders)

### Building from Source

To compile the plugin JAR:

```bash
mvn clean package
```

The compiled output will be located in `target/PPPPlugin-1.0.jar`.

---

## License

This project is licensed under the [MIT License](LICENSE).
