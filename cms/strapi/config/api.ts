import type { Core } from '@strapi/strapi';

const config: Core.Config.Api = {
  rest: {
    defaultLimit: 25,
    maxLimit: 1000,
    withCount: true,
    strictParams: true,
  },
  documents: {
    strictParams: true,
    strictRelations: true,
  },
};

export default config;
