package io.stormbird.wallet.repository;


import android.arch.lifecycle.MutableLiveData;
import io.stormbird.wallet.C;
import io.stormbird.wallet.entity.GasSettings;

import io.stormbird.wallet.util.BalanceUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.http.HttpService;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;

import static io.stormbird.wallet.C.GAS_LIMIT_MIN;
import static io.stormbird.wallet.C.GAS_PER_BYTE;

public class GasSettingsRepository implements GasSettingsRepositoryType
{
    private final EthereumNetworkRepositoryType networkRepository;
    private BigInteger cachedGasPrice;
    private int currentChainId;
    private Disposable gasSettingsDisposable;

    private final MutableLiveData<BigInteger> gasPrice = new MutableLiveData<>();

    private final static long FETCH_GAS_PRICE_INTERVAL = 30;

    public GasSettingsRepository(EthereumNetworkRepositoryType networkRepository) {
        this.networkRepository = networkRepository;

        setCachedPrice();

        gasSettingsDisposable = Observable.interval(0, FETCH_GAS_PRICE_INTERVAL, TimeUnit.SECONDS)
                .doOnNext(l ->
                        fetchGasSettings()
                ).subscribe();
    }

    private void setCachedPrice()
    {
        if (networkRepository.getDefaultNetwork() != null)
        {
            this.currentChainId = networkRepository.getDefaultNetwork().chainId;
        }
        else currentChainId = EthereumNetworkRepository.MAINNET_ID;

        switch (currentChainId)
        {
            case EthereumNetworkRepository.XDAI_ID:
                cachedGasPrice = new BigInteger(C.DEFAULT_XDAI_GAS_PRICE);
                break;
            default:
                cachedGasPrice = new BigInteger(C.DEFAULT_GAS_PRICE);
                break;
        }
    }

    private void fetchGasSettings() {

        final Web3j web3j = Web3j.build(new HttpService(networkRepository.getDefaultNetwork().rpcServerUrl));

        try {
            EthGasPrice price = web3j
                    .ethGasPrice()
                    .send();
            if (price.getGasPrice().compareTo(BalanceUtils.gweiToWei(BigDecimal.ONE)) >= 0)
            {
                cachedGasPrice = price.getGasPrice();
                gasPrice.postValue(cachedGasPrice);
            }
            else if (networkRepository.getDefaultNetwork().chainId != currentChainId)
            {
                //didn't update the current price correctly, switch to default:
                setCachedPrice();
            }
        } catch (Exception ex) {
            // silently
        }
    }

    @Override
    public MutableLiveData<BigInteger> gasPriceUpdate()
    {
        return gasPrice;
    }

    public Single<GasSettings> getGasSettings(boolean forTokenTransfer) {
        return Single.fromCallable( () -> {
            BigInteger gasLimit = new BigInteger(C.DEFAULT_GAS_LIMIT);
            if (forTokenTransfer) {
                gasLimit = new BigInteger(C.DEFAULT_GAS_LIMIT_FOR_TOKENS);
            }
            return new GasSettings(cachedGasPrice, gasLimit);
        });
    }

    public Single<GasSettings> getGasSettings(byte[] transactionBytes, boolean isNonFungible) {
        return Single.fromCallable( () -> {
            BigInteger gasLimit = new BigInteger(C.DEFAULT_GAS_LIMIT);
            if (transactionBytes != null) {
                if (isNonFungible)
                {
                    gasLimit = new BigInteger(C.DEFAULT_GAS_LIMIT_FOR_NONFUNGIBLE_TOKENS);
                }
                else
                {
                    gasLimit = new BigInteger(C.DEFAULT_GAS_LIMIT_FOR_TOKENS);
                }
                BigInteger estimate = estimateGasLimit(transactionBytes);
                if (estimate.compareTo(gasLimit) > 0) gasLimit = estimate;
            }
            return new GasSettings(cachedGasPrice, gasLimit);
        });
    }

    private BigInteger estimateGasLimit(byte[] data)
    {
        BigInteger roundingFactor = BigInteger.valueOf(10000);
        BigInteger txMin = BigInteger.valueOf(GAS_LIMIT_MIN);
        BigInteger bytePrice = BigInteger.valueOf(GAS_PER_BYTE);
        BigInteger dataLength = BigInteger.valueOf(data.length);
        BigInteger estimate = bytePrice.multiply(dataLength).add(txMin);
        estimate = estimate.divide(roundingFactor).add(BigInteger.ONE).multiply(roundingFactor);
        return estimate;
    }
}
