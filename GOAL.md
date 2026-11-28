# GOAL: Bang Master Module with Legit/Normal Modes
Status: IN PROGRESS
Last updated: 2026-11-27

## Requirements
- [ ] R1  Create Bang module with enable/disable toggle
- [ ] R2  Add height offset setting (double, default 0.2)
- [ ] R3  Add distance from target setting (double, default contact + 0.18)
- [ ] R4  Add mode setting: Legit vs Normal
- [ ] R5  Add position setting: Front vs Back
- [ ] R6  Legit mode: Baritone walks to player, then old walk+crouch animation
- [ ] R7  Normal mode: Current hover/lock with leg swing animation
- [ ] R8  Command `.bang` starts module with target/speed; `.unbang` or `.bang` stops
- [ ] R9  Module respects settings when active
- [ ] R10 Register module in ShamaAddon
- [ ] R11 Update README with module documentation
- [ ] R12 Build and verify compilation
- [ ] R13 Create GitHub PR
- [ ] R14 Record videos of both modes

## Constraints
- C1  Must integrate with existing BaritoneBridge utility
- C2  Must work with existing mixin system
- C3  Settings must be properly saved/loaded via Meteor's config system

## Sweep log
- Sweep 1 (coverage): R1-R14 identified