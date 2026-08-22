/** @file mobi_cover.h
 *
 * Copyright (c) 2020 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * Licensed under LGPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

#ifndef PAGENEST_MOBI_COVER_H
#define PAGENEST_MOBI_COVER_H

#include "../../libmobi/src/mobi.h"

#ifdef __cplusplus
extern "C" {
#endif

int mobi_dump_cover(
        const MOBIData *m,
        const char *title,
        const char *target_dir,
        char **target_path);

#ifdef __cplusplus
}
#endif

#endif
