# The user Lambda's contract

`users-api.yaml` is the contract of everything the user Lambda serves.

Change it first, in its own pull request or commit, then the code. `ContractTest` checks the handler's responses
against it, and the frontend generates its types from it (`npm run api-types` in coolture-frontend).
