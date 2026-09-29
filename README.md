# Pitsch

## Overview

Pitsch is an AI-powered platform designed to help manage pitches, conversations, and follow-up workflows more efficiently.

The project focuses on using AI agents to reduce repetitive manual work involved in handling incoming pitches and related conversations.

Instead of requiring users to manually inspect every conversation, Pitsch aims to analyze the available information, identify relevant context, and surface actions that may require attention.

---

## Problem

Managing multiple pitches and conversations manually can become time-consuming.

Important tasks such as:

- Identifying whether a new message is related to an existing pitch
- Detecting conversations that may require follow-up
- Identifying date or information mismatches
- Finding missing information
- Determining what questions should be asked next

can require significant manual effort.

Pitsch aims to simplify this process using AI-driven automation.

---

## Solution

Pitsch uses an agent-based AI approach where different tasks can be handled by specialized agents.

The system is being developed to assist with:

- Pitch and conversation analysis
- Matching new messages with previous pitches
- Follow-up identification
- Detecting inconsistencies such as date mismatches
- Identifying missing information
- Generating relevant questions when additional information is required

The exact implementation and agent workflow will evolve as the project is developed and tested.

---

## AI Agent Approach

The project follows a **multi-agent approach** rather than relying on a single AI component for every task.

Specialized agents can be used for different responsibilities, while the overall system coordinates their results to produce useful and actionable information.

The agent architecture is being developed incrementally as part of the project.

---

## Technology Stack

### Backend
- Java
- Spring Boot
- Maven

### Frontend
- To be implemented

### AI
- AI / LLM-based components
- Agent-based workflows

### Development
- Git
- GitHub
- Visual Studio Code

---

## Project Structure

The repository contains separate development components for the different parts of the application.

Each component is developed independently and integrated through the main branch.

---

## Current Progress

### Backend

The initial Spring Boot backend has been successfully created and configured.

The application currently runs locally on:

```text
http://localhost:8080
