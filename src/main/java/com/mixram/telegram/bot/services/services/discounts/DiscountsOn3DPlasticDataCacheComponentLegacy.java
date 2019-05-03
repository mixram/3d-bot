package com.mixram.telegram.bot.services.services.discounts;

import com.mixram.telegram.bot.config.cache.RedisTemplateHelper;
import com.mixram.telegram.bot.services.domain.entity.Data3DPlastic;
import com.mixram.telegram.bot.services.domain.enums.Shop3D;
import com.mixram.telegram.bot.services.modules.Module3DPlasticDataSearcher;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author mixram on 2019-03-29.
 * @since 0.1.1.0
 * @deprecated legacy since 1.4.2.0, use 'v2' instead.
 */
@Deprecated
@Log4j2
//@Component
public class DiscountsOn3DPlasticDataCacheComponentLegacy implements Module3DPlasticDataSearcher {

    // <editor-fold defaultstate="collapsed" desc="***API elements***">

    private final RedisTemplateHelper redisTemplate;

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="***Util elements***">

    @Autowired
    public DiscountsOn3DPlasticDataCacheComponentLegacy(RedisTemplateHelper redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // </editor-fold>


    @Override
    public Data3DPlastic search(Shop3D shop) {
        return redisTemplate.getPlasticFromRedis(shop);
    }

    @Override
    public Data3DPlastic searchOld(Shop3D shop) {
        return redisTemplate.getOldPlasticFromRedis(shop);
    }

    // <editor-fold defaultstate="collapsed" desc="***Private elements***">

    //

    // </editor-fold>
}
